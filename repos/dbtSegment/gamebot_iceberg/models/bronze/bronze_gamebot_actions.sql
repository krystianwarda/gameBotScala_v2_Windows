{{ config(
    materialized='table',
    schema='gamebot_bronze',
    alias='bronze_gamebot_actions'
) }}

-- Bronze layer: bot actions
-- Direct copy of the raw actions data with metadata intact.

select
    device,
    action,
    x,
    y,
    metaGeneratedTimestamp,
    metaGeneratedDate,
    metaGeneratedId
from {{ source('gamebot_raw_staging', 'raw_gamebot_actions') }}
