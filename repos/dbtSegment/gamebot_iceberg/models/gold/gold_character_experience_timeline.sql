{{ config(
    materialized='table',
    schema='gamebot_gold',
    alias='gold_character_experience_timeline'
) }}

-- Gold layer: Character experience progression over time
-- Aggregated to minute level with max experience per minute
-- Optimized for time-series analysis and graphing

with character_experience_raw as (
    select
        TIMESTAMP_TRUNC(TIMESTAMP_SECONDS(UNIX_SECONDS(event_timestamp)), MINUTE) as event_timedate,
        character_id,
        character_name,
        character_level,
        experience_points
    from {{ ref('silver_character_info') }}
    where character_id is not null
        and experience_points is not null
)

select
    event_timedate,
    character_id,
    MAX(character_name) as character_name,
    MAX(character_level) as character_level,
    MAX(experience_points) as experience_points
from character_experience_raw
group by event_timedate, character_id
order by character_id, event_timedate