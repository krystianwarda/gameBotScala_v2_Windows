{{ config(
    materialized='table',
    schema='gamebot_bronze',
    alias='bronze_meta'
) }}

-- Bronze layer: unified meta registry
-- Combine distinct meta information from both raw datasets.

with meta_from_events as (
    select distinct
        metaGeneratedId,
        metaGeneratedTimestamp,
        metaGeneratedDate,
        metaProcessedTimestamp,
        'game_events' as metaSource
    from {{ ref('bronze_game_events') }}
),

meta_from_actions as (
    select distinct
        metaGeneratedId,
        metaGeneratedTimestamp,
        metaGeneratedDate,
        CAST(null AS TIMESTAMP) as metaProcessedTimestamp,
        'gamebot_actions' as metaSource
    from {{ ref('bronze_gamebot_actions') }}
)

select distinct
    metaGeneratedId,
    metaGeneratedTimestamp,
    metaGeneratedDate,
    metaProcessedTimestamp,
    metaSource
from (
    select * from meta_from_events
    union all
    select * from meta_from_actions
)