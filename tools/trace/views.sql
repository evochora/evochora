-- Views over a trace directory and the query every session needs first.
-- Start duckdb inside the trace directory and run:  .read /path/to/tools/trace/views.sql
CREATE OR REPLACE VIEW steps AS SELECT * FROM read_csv('steps.tsv', delim='\t', header=true, quote='');
CREATE OR REPLACE VIEW state AS SELECT * FROM read_csv('state.tsv', delim='\t', header=true, quote='');
CREATE OR REPLACE VIEW cells AS SELECT * FROM read_csv('cells.tsv', delim='\t', header=true, quote='');

-- The occupied cells of the world at tick t: the last full set at or before t, then every
-- change up to t. Use: SELECT * FROM world_at(12345) WHERE x BETWEEN 10 AND 40;
CREATE OR REPLACE MACRO world_at(t) AS TABLE
  WITH base AS (SELECT max(tick) AS b FROM cells WHERE kind = 'full' AND tick <= t),
       latest AS (SELECT x, y, type, value, marker, owner,
                         row_number() OVER (PARTITION BY x, y ORDER BY tick DESC) AS rn
                  FROM cells, base WHERE tick BETWEEN base.b AND t)
  SELECT x, y, type, value, marker, owner FROM latest
  WHERE rn = 1 AND NOT (type = 'CODE' AND value = 0 AND owner = 0);

-- The world at the last recorded tick.
CREATE OR REPLACE MACRO world_end() AS TABLE
  SELECT * FROM world_at((SELECT max(tick) FROM cells));
