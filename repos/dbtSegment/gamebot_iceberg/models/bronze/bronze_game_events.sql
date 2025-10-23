{{ config(
    materialized='table',
    schema='gamebot_bronze',
    alias='bronze_game_events'
) }}

select
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
    hotkeysBinds,
    characterInfo,
    focusedTabInfo,
    metaGeneratedDate,
    metaGeneratedTimestamp,
    metaGeneratedId,
    metaProcessedTimestamp,
    -- Derive machine_id from character name for historical data
    COALESCE(
        JSON_EXTRACT_SCALAR(characterInfo, '$.Name'),
        'unknown_machine'
    ) as machine_id,
    -- Generate composite unique event ID
    CONCAT(
        COALESCE(JSON_EXTRACT_SCALAR(characterInfo, '$.Name'), 'unknown'),
        '_',
        CAST(metaGeneratedId AS STRING)
    ) as composite_event_id
from {{ source('gamebot_raw_staging', 'raw_game_events') }}