{{ config(materialized='view') }}
-- models/example/first_model.sql

with raw_data as (
    select
        raw_json
    from {{ source('gamebot_raw_staging', 'raw_game_snapshots') }}
),

parsed as (
    select
        JSON_VALUE(raw_json, '$.screenInfo.posX') as posX,
        JSON_VALUE(raw_json, '$.screenInfo.posY') as posY,
        JSON_VALUE(raw_json, '$.characterInfo.name') as creature
    from raw_data
)

select *
from parsed
