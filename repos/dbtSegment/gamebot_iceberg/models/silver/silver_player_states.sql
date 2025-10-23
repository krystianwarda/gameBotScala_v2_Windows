{{ config(
    materialized='table',
    schema='gamebot_silver',
    alias='silver_player_states'
) }}

-- Silver player states: parsed PlayerStates from characterInfo
select
    metaGeneratedId as event_id,
    metaGeneratedTimestamp as event_timestamp,
    JSON_EXTRACT_SCALAR(characterInfo, '$.Id') as character_id,
    JSON_EXTRACT_SCALAR(characterInfo, '$.Name') as character_name,
    
    -- Status Effects (negative)
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Cursed') AS BOOL) as is_cursed,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Drowning') AS BOOL) as is_drowning,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Hungry') AS BOOL) as is_hungry,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Freezing') AS BOOL) as is_freezing,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Bleeding') AS BOOL) as is_bleeding,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Dazzled') AS BOOL) as is_dazzled,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Burn') AS BOOL) as is_burning,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Paralyze') AS BOOL) as is_paralyzed,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Drunk') AS BOOL) as is_drunk,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Poison') AS BOOL) as is_poisoned,
    
    -- Status Effects (positive/buffs)
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Haste') AS BOOL) as has_haste,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.PartyBuff') AS BOOL) as has_party_buff,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Energy') AS BOOL) as has_energy,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Swords') AS BOOL) as has_swords_buff,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.ManaShield') AS BOOL) as has_mana_shield,
    
    -- Protection Zones
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.Pz') AS BOOL) as is_in_protection_zone,
    CAST(JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates.PzBlock') AS BOOL) as has_pz_block

from {{ ref('bronze_game_events') }}
where characterInfo is not null
    and JSON_EXTRACT_SCALAR(characterInfo, '$.Id') is not null
    and JSON_EXTRACT_SCALAR(characterInfo, '$.PlayerStates') is not null