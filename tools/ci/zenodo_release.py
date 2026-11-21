#!/usr/bin/env python3
"""Archives a release of Evochora on Zenodo, in the three steps the release workflow takes.

``reserve``  finds the draft of the record for a version, or creates it, and reserves its DOI. The
             DOI exists from then on and is written into the build and the text of the release; it
             resolves only once the record is published. A release that follows an archived one
             becomes a new version of its record, which CITATION.cff names by the DOI that stands
             for all versions.
``fill``     gives the draft its metadata, from CITATION.cff and the text of the release, and its
             files, and compares the checksum Zenodo reports for each file with the file's own.
             One of the files can be named as the one Zenodo shows first: it shows a preview of
             a record's first file otherwise, and cannot preview an archive of this size. That
             file says what the others are, in a table whose first column names each of them
             in backquotes, and is held against them before anything is sent: the names of that
             column have to be the names of the other files, all of them and no other, since a
             published record keeps what it says for good.
``publish``  publishes the draft. A published record can never be deleted, which is why this is a
             step of its own, taken with a token of its own after the maintainer has approved.

Every step can be repeated: ``reserve`` returns the draft an earlier run created, ``fill`` replaces
what the draft holds, and ``publish`` of a record that is published already changes nothing.
Nothing is created for a version that is archived already.

The token is read from the environment variable ZENODO_TOKEN. Creating and filling a draft needs
the scope deposit:write, publishing needs deposit:actions as well. Uses the standard library only,
so that it runs on every runner as it is. Exits with 0 on success, 1 otherwise.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

# Zenodo asks automated clients to name themselves instead of sending a library's default
USER_AGENT = "evochora-release-workflow (https://github.com/evochora/evochora)"
# Asked for plain JSON, Zenodo answers in the form of its former interface, with other names and
# without the DOI of a draft
RECORD_FORMAT = "application/vnd.inveniordm.v1+json"
RESOURCE_TYPE = "software"
CHUNK = 1 << 20


class ZenodoError(Exception):
    """A request Zenodo refused, or a state of the records this script does not continue from."""


class Zenodo:
    """The requests of this script to one Zenodo system, each authorized with the token."""

    def __init__(self, url: str, token: str):
        self.api = url.rstrip("/") + "/api"
        self.token = token

    def request(self, method: str, path: str, body=None, data=None, length=None, expect=(200, 201, 202, 204)):
        """Sends one request and returns the parsed JSON of the answer, or None where it has none.

        ``body`` is sent as JSON; ``data`` is sent as it is, a file opened for reading among it,
        with ``length`` as its size.
        """
        headers = {"Authorization": f"Bearer {self.token}", "User-Agent": USER_AGENT, "Accept": RECORD_FORMAT}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        elif data is not None:
            headers["Content-Type"] = "application/octet-stream"
            headers["Content-Length"] = str(length)
        request = urllib.request.Request(self.api + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=600) as response:
                status, text = response.status, response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            status, text = error.code, error.read().decode("utf-8", errors="replace")
        if status not in expect:
            raise ZenodoError(f"{method} {path} answered {status}: {text[:2000]}")
        return json.loads(text) if text.strip() else None

    def own_records(self, query: str) -> list:
        """The records and drafts of the token's owner that match the query, newest first."""
        found, page = [], 1
        while True:
            parameters = urllib.parse.urlencode({"q": query, "sort": "newest", "size": 50, "page": page})
            hits = self.request("GET", f"/user/records?{parameters}")["hits"]
            found += hits["hits"]
            if len(found) >= hits["total"] or not hits["hits"]:
                return found
            page += 1


def metadata_of(citation: dict, version: str, description: str) -> dict:
    """The metadata of the record: who and what from CITATION.cff, the version and its text from the release."""
    creators = []
    for author in citation["authors"]:
        person = {"type": "personal", "given_name": author["given-names"], "family_name": author["family-names"]}
        if author.get("orcid"):
            person["identifiers"] = [{"scheme": "orcid", "identifier": author["orcid"].rsplit("/", 1)[-1]}]
        creators.append({"person_or_org": person})
    repository = citation["repository-code"].rstrip("/")
    return {
        "resource_type": {"id": RESOURCE_TYPE},
        "title": citation["title"],
        "creators": creators,
        "description": description,
        "version": version,
        "publication_date": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
        "publisher": "Zenodo",
        "rights": [{"id": str(citation["license"]).lower()}],
        "related_identifiers": [{
            "identifier": f"{repository}/tree/{version}",
            "scheme": "url",
            "relation_type": {"id": "issupplementto"},
            "resource_type": {"id": RESOURCE_TYPE},
        }],
    }


def body_of(draft: dict, metadata: dict) -> dict:
    """What is sent to change a draft: its metadata anew, and of what Zenodo returned only what a draft keeps.

    Zenodo rejects parts of a draft it delivered itself, such as the titles it adds to a license.
    """
    return {"access": draft["access"], "files": {"enabled": True}, "pids": draft.get("pids", {}), "metadata": metadata}


def is_ours(record: dict, title: str) -> bool:
    metadata = record.get("metadata", {})
    return metadata.get("title") == title and metadata.get("resource_type", {}).get("id") == RESOURCE_TYPE


def concept_of(citation: dict):
    """The id of the record that stands for all versions, which the DOI in CITATION.cff ends with, or None."""
    doi = str(citation.get("doi") or "")
    match = re.fullmatch(r"10\.\d+/zenodo\.(\d+)", doi)
    if doi and not match:
        raise ZenodoError(f"CITATION.cff names the DOI '{doi}', which is no DOI of a Zenodo record")
    return match.group(1) if match else None


def reserve(zenodo: Zenodo, citation: dict, version: str) -> dict:
    """Returns the draft for the version with its DOI reserved, creating the draft where there is none."""
    title = citation["title"]
    own = [record for record in zenodo.own_records(f'metadata.version:"{version}"') if is_ours(record, title)]
    own = [record for record in own if record["metadata"].get("version") == version]
    if any(record.get("is_published") for record in own):
        raise ZenodoError(f"{version} is archived already: {[r['links'].get('self_html') for r in own if r.get('is_published')]}")
    if len(own) > 1:
        raise ZenodoError(f"found {len(own)} drafts for {version}; discard all but one at Zenodo: {[r['id'] for r in own]}")
    if own:
        # The search returns a draft without the DOI reserved for it
        draft = zenodo.request("GET", f"/records/{own[0]['id']}/draft")
    else:
        published = [r for r in zenodo.own_records(f'metadata.title:"{title}"') if is_ours(r, title) and r.get("is_published")]
        concept = concept_of(citation)
        if concept:
            latest = [r for r in published if str(r.get("parent", {}).get("id")) == concept]
            if not latest:
                raise ZenodoError(f"CITATION.cff names the record {concept} for all versions, and the token's owner has no published version of it")
            # A new version starts without the version and the date of the one before it, and must
            # name its own before it can be found again by the search above
            draft = zenodo.request("POST", f"/records/{latest[0]['id']}/versions")
            draft = zenodo.request("PUT", f"/records/{draft['id']}/draft", body=body_of(draft, metadata_of(citation, version, "")))
        elif published:
            raise ZenodoError("Evochora is archived already, and CITATION.cff names no DOI: enter the DOI that stands for all "
                              f"versions there, so that {version} becomes a version of that record and not a record of its own")
        else:
            draft = zenodo.request("POST", "/records", body={
                "access": {"record": "public", "files": "public"}, "files": {"enabled": True},
                "metadata": metadata_of(citation, version, "")})
    if not draft.get("pids", {}).get("doi", {}).get("identifier"):
        draft = zenodo.request("POST", f"/records/{draft['id']}/draft/pids/doi")
    return draft


def md5_of(path: Path) -> str:
    digest = hashlib.md5()  # the checksum Zenodo reports for a file, compared here and not relied on
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fill(zenodo: Zenodo, citation: dict, record: str, version: str, description: str, files: list, preview=None) -> None:
    """Sets the metadata of the draft, replaces its files with the given ones and names the one to preview."""
    names = [path.name for path in files]
    if len(set(names)) != len(names):
        raise ZenodoError(f"two files of the same name: {names}")
    if preview and preview not in names:
        raise ZenodoError(f"the file to preview, {preview}, is none of the files: {names}")
    if preview:
        # The first cell of each row of a table; backquotes anywhere else are ordinary Markdown
        described = set(re.findall(r"^\|\s*`([^`]+)`", files[names.index(preview)].read_text(encoding="utf-8"), re.MULTILINE))
        others = set(names) - {preview}
        if described != others:
            raise ZenodoError(f"{preview} does not describe the files of the record: it names "
                              f"{sorted(described - others) or 'nothing'} that is not among them, and leaves out "
                              f"{sorted(others - described) or 'nothing'}")
    draft = zenodo.request("GET", f"/records/{record}/draft")
    if draft["metadata"].get("version") != version:
        raise ZenodoError(f"the draft {record} is of version {draft['metadata'].get('version')}, not {version}")
    zenodo.request("PUT", f"/records/{record}/draft", body=body_of(draft, metadata_of(citation, version, description)))
    for entry in zenodo.request("GET", f"/records/{record}/draft/files").get("entries", []):
        zenodo.request("DELETE", f"/records/{record}/draft/files/{urllib.parse.quote(entry['key'])}")
    for path in files:
        key = urllib.parse.quote(path.name)
        zenodo.request("POST", f"/records/{record}/draft/files", body=[{"key": path.name}])
        with path.open("rb") as stream:
            zenodo.request("PUT", f"/records/{record}/draft/files/{key}/content", data=stream, length=path.stat().st_size)
        committed = zenodo.request("POST", f"/records/{record}/draft/files/{key}/commit")
        reported, own = committed.get("checksum", ""), "md5:" + md5_of(path)
        if reported != own:
            raise ZenodoError(f"Zenodo reports the checksum {reported} for {path.name}, and the file has {own}")
        print(f"uploaded {path.name} ({path.stat().st_size} bytes, {own})", flush=True)
    if preview:
        # Only a file the draft holds can be named, so this follows the uploads
        body = body_of(zenodo.request("GET", f"/records/{record}/draft"), metadata_of(citation, version, description))
        body["files"]["default_preview"] = preview
        zenodo.request("PUT", f"/records/{record}/draft", body=body)


def publish(zenodo: Zenodo, record: str) -> dict:
    """Publishes the draft and returns the record; returns the record as it is where it is published already."""
    answer = zenodo.request("GET", f"/records/{record}", expect=(200, 404))
    if answer and answer.get("is_published"):
        return answer
    return zenodo.request("POST", f"/records/{record}/draft/actions/publish")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--url", default="https://zenodo.org", help="the Zenodo system, https://sandbox.zenodo.org for trying")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("reserve", "fill"):
        command = commands.add_parser(name)
        command.add_argument("--citation", type=Path, required=True, help="CITATION.cff as JSON")
        command.add_argument("--version", required=True, help="the version of the release, as its tag names it")
    filling = commands.choices["fill"]
    filling.add_argument("--record", required=True, help="the id of the draft, as reserve printed it")
    filling.add_argument("--description", type=Path, required=True, help="the text of the release as HTML")
    filling.add_argument("--preview", help="the name of the file Zenodo shows first")
    filling.add_argument("files", type=Path, nargs="+", help="the files of the record")
    commands.add_parser("publish").add_argument("--record", required=True, help="the id of the draft")
    arguments = parser.parse_args()

    token = os.environ.get("ZENODO_TOKEN", "")
    if not token:
        print("ZENODO_TOKEN is not set", file=sys.stderr)
        return 1
    zenodo = Zenodo(arguments.url, token)
    try:
        if arguments.command == "reserve":
            draft = reserve(zenodo, json.loads(arguments.citation.read_text(encoding="utf-8")), arguments.version)
            print(f"record={draft['id']}")
            print(f"doi={draft['pids']['doi']['identifier']}")
        elif arguments.command == "fill":
            fill(zenodo, json.loads(arguments.citation.read_text(encoding="utf-8")), arguments.record,
                 arguments.version, arguments.description.read_text(encoding="utf-8"), arguments.files,
                 arguments.preview)
        else:
            record = publish(zenodo, arguments.record)
            print(f"published {record['pids']['doi']['identifier']}: {record['links'].get('self_html', '')}")
    except ZenodoError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
