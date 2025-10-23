{{ config(
    materialized='table',
    schema='gamebot_silver',
    alias='silver_character_info'
) }}

-- Silver character info: parsed character data from game events
select
    metaGeneratedId as event_id,
    metaGeneratedTimestamp as event_timestamp,
    
    -- Character Identity
    JSON_EXTRACT_SCALAR(characterInfo, '$.Id') as character_id,
    JSON_EXTRACT_SCALAR(characterInfo, '$.Name') as character_name,
    
    -- Character Stats
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.Level') AS INT64) as character_level,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.Experience') AS INT64) as experience_points,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.Vocation') AS INT64) as vocation_id,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.MagicLevel') AS INT64) as magic_level,
    
    -- Health & Mana
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.Health') AS INT64) as current_health,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.HealthPercent') AS INT64) as health_percent,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.Mana') AS INT64) as current_mana,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.ManaMax') AS INT64) as max_mana,
    
    -- Position
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PositionX') AS INT64) as position_x,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PositionY') AS INT64) as position_y,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PositionZ') AS INT64) as position_z,
    
    -- Capacity
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.TotalCapacity') AS INT64) as total_capacity,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.FreeCap') AS INT64) as free_capacity,
    
    -- Combat Modes
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.ChaseMode') AS INT64) as chase_mode,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.FightMode') AS INT64) as fight_mode,
    
    -- Flags
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.IsCrosshairActive') AS BOOL) as is_crosshair_active,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.IsPartyMember') AS BOOL) as is_party_member,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.RegTime') AS INT64) as registration_time

from {{ ref('bronze_game_events') }}
where characterInfo is not null
    and JSON_EXTRACT_SCALAR(characterInfo, '$.Id') is not null