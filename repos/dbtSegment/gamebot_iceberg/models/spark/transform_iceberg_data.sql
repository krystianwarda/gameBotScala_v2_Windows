{# {{ config(
    materialized='table',
    schema='gcs_db',
    alias='transformed_game_events'
) }}

SELECT
  screenInfo,
  battleInfo,
  textTabsInfo,
  EqInfo,
  containersInfo,
  attackInfo,
  areaInfo,
  spyLevelInfo,
  lastAttackedCreatureInfo,
  lastKilledCreatures,
  characterInfo,
  focusedTabInfo,
  processing_time,
  pt_date
FROM game_events
WHERE pt_date >= current_date() - INTERVAL 7 DAYS #}
