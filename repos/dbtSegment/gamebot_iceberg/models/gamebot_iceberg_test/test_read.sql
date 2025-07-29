{{ config(
    materialized='view'
) }}

SELECT * FROM game_events LIMIT 10