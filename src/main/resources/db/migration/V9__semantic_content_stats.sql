create table SEMANTIC_CONTENT_STATS
(
    ID               VARCHAR(256) PRIMARY KEY,
    HARVESTER_RUN_ID VARCHAR(256) NOT NULL REFERENCES HARVESTER_RUN (ID),
    RESOURCE_URI     VARCHAR(255) NOT NULL,
    RESOURCE_TYPE    VARCHAR(64)  NOT NULL,
    RIGHT_HOLDER     VARCHAR(64)  NOT NULL,
    ISSUED_ON        TIMESTAMP,
    MODIFIED_ON      TIMESTAMP,
    HAS_ERRORS       BOOLEAN      NOT NULL,
    HAS_WARNINGS     BOOLEAN      NOT NULL,
    STATUS           TEXT         NOT NULL
) ENGINE = InnoDB;

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