Third-party software in this distribution
=========================================

Evochora itself is licensed under the MIT License; see LICENSE in the directory above. It
ships third-party software under its own licenses, listed and reproduced here.

java-libraries.txt
    Every Java library in lib/, with its version, its licenses and its project URL.

java/<library>.jar/
    The license, notice and similar files the library's jar carries itself.

java/<group>/
    License and copyright notice for libraries whose jars carry no license file of their own,
    taken from the library's source repository.

frontend-libraries.txt
    Every library the web frontend serves, with its version, its license and its homepage.

frontend/
    The license and notice files of those libraries, the same files the node serves next to
    each library under web/shared/vendor/.

texts/
    The full text of each license that the libraries name without carrying it.

docker-image.txt
    Present in the Docker image only: where the sources of the operating system packages and
    of the Java runtime in the image are published.

Source code of the libraries is available from the project URLs in java-libraries.txt and
frontend-libraries.txt.
