-- this variable will hold a "pointer" for our event below
local timerEvent = nil

function init()
    -- bind game start and end events
    connect(g_game, {
        onGameStart = gameStarted,
        onGameEnd = gameEnded
    })
end


-- this function will be called when we enter in a world
function gameStarted()
    
    -- create a repeated event
    timerEvent = cycleEvent(tick, 500) -- 500 = 500 milliseconds or 0.5 seconds 
end

-- this function will be called according to the settings made in gameStarted()
function tick()
    displayList() -- Call displayList here
end


function displayList()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local dimension = modules.game_interface.getMapPanel():getVisibleDimension()
    local spectators = g_map.getSpectatorsInRangeEx(player:getPosition(), false, math.floor(dimension.width / 2), math.floor(dimension.width / 2) + 1, math.floor(dimension.height / 2), math.floor(dimension.height / 2) + 1)

    -- Initialize the main JSON-like structure
    local gameData = {
        characterInfo = {},
        battleInfo = {}
    }

    -- Populate characterInfo
    gameData.characterInfo = {
        Name = player:getName(),
        Id = player:getId(),
        --Voc = player:getVocation(),
        Health = player:getHealth(),
        --HealthMax = player:getMaxHealth(),
        HealthPercent = player:getHealthPercent(),
        Mana = player:getMana(),
        ManaMax = player:getMaxMana(),
        PositionX = player:getPosition().x,
        PositionY = player:getPosition().y,
        PositionZ = player:getPosition().z,
        -- MagicLevel = player:getMagicLevel(),
        -- Blessings = player:getBlessings(),
    }

    -- Collect data of creatures in range for battle information
    for _, creature in pairs(spectators) do
        if checkValidCreatureForListing(creature) then
            table.insert(gameData.battleInfo, {
                Name = creature:getName(),
                Id = creature:getId(),
                HealthPercent = creature:getHealthPercent(),
                PositionX = creature:getPosition().x,
                PositionY = creature:getPosition().y,
                PositionZ = creature:getPosition().z
            })
        end
    end

    -- Convert the game data to a JSON-like string
    local gameDataString = tableToJsonString(gameData, 0)

    -- Output the structured data
    printConsole(gameDataString)
end



-- Updated function to convert a Lua table to a JSON-like string with nested structures
function tableToJsonString(tbl, indentLevel)
    indentLevel = indentLevel or 0
    local indent = string.rep("  ", indentLevel)
    local result = {}

    for key, v in pairs(tbl) do
        local elementString
        if type(v) == "table" then
            elementString = indent .. '"' .. key .. '": {\n' .. tableToJsonString(v, indentLevel + 1) .. '\n' .. indent .. '}'
        else
            elementString = indent .. '"' .. key .. '": ' .. (type(v) == "string" and '"' .. v .. '"' or v)
        end
        table.insert(result, elementString)
    end

    return "{\n" .. table.concat(result, ",\n") .. "\n" .. string.rep("  ", indentLevel - 1) .. "}"
end


function displayListSource()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end
    
    local player = g_game.getLocalPlayer()
    
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end
    
    local dimension = modules.game_interface.getMapPanel():getVisibleDimension()
    local spectators = g_map.getSpectatorsInRangeEx(player:getPosition(), false, math.floor(dimension.width / 2), math.floor(dimension.width / 2) + 1, math.floor(dimension.height / 2), math.floor(dimension.height / 2) + 1)

    -- run the following code in protected mode (same as try/catch)
    local success, errmsg = pcall(function()
        -- (try)
        printConsole('Listing creatures:')
        for index, creature in pairs(spectators) do
            if checkValidCreatureForListing(creature) then
                printConsole('[#' .. tostring(index) .. '] ' .. creature:getName() .. ': Health % = ' .. tostring(creature:getHealthPercent())  .. ': XPos % = ' .. tostring(creature:getPosition().x))
            end
        end
    end)
    
    -- was there an error? (catch)
    if not success then
        printConsole('Error caught: ' .. errmsg)
    end
end


-- copied and modified from https://github.com/OTCv8/otcv8-dev/blob/master/modules/game_battle/battle.lua#L293-L326
function checkValidCreatureForListing(c)
    -- I think this part is self-explanatory
    if c:isLocalPlayer() then return false end
    if c:getHealthPercent() <= 0 then return false end
    
    local pos = c:getPosition()
    local player = g_game.getLocalPlayer()
    
    if not pos then return false end
    if pos.z ~= player:getPosition().z or not c:canBeSeen() then return false end
    
    -- no further filtering like in the original one
    return true
end

-- this function will be called when we exit from a world
function gameEnded()
    
    -- do we have the event?
    if timerEvent ~= nil then
        -- if so, remove it
        removeEvent(timerEvent)
    end
    
    printConsole('quit from game world')
    
end

-- call our main function
init()