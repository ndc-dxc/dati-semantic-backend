alter table SEMANTIC_CONTENT_STATS
    modify ISSUED_ON timestamp null;

alter table SEMANTIC_CONTENT_STATS
    modify MODIFIED_ON timestamp null;

CREATE VIEW LATEST_HARVESTER_RUN_BY_YEAR AS
WITH LATEST_RUN_BY_YEAR AS (SELECT REPOSITORY_ID,
                                   YEAR(STARTED) AS YEAR,
                                   MAX(STARTED)  AS LATEST_STARTED
                            FROM HARVESTER_RUN
                            GROUP BY REPOSITORY_ID, YEAR(STARTED))
SELECT HR.*
FROM HARVESTER_RUN HR
         JOIN LATEST_RUN_BY_YEAR LRBY
              ON HR.REPOSITORY_ID = LRBY.REPOSITORY_ID
                  AND YEAR(HR.STARTED) = LRBY.YEAR
                  AND HR.STARTED = LRBY.LATEST_STARTED;