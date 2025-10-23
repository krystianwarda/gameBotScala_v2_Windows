{{ config(
    materialized='table',
    schema='gamebot_silver',
    alias='silver_meta'
) }}

-- Silver meta: cleaned and deduplicated meta registry
select distinct
    metaGeneratedId as event_id,
    metaGeneratedTimestamp as event_timestamp,
    metaGeneratedDate as event_date,
    metaProcessedTimestamp as processed_timestamp,
    metaSource as source_system
from {{ ref('bronze_meta') }}
where metaGeneratedId is not null