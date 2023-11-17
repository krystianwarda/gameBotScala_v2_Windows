function init()

-- bind (register) change events for attacking and following creatures (they will be only run when you attack or follow something explicitly)

connect(g_game, {
    onAttackingCreatureChange = evt_attackingCreatureChanged,
    onFollowingCreatureChange = evt_followingCreatureChanged,
})

-- printConsole() prints to our console with the file name of this script
printConsole('Registered events')

-- register some keyboard shortcuts
g_keyboard.bindKeyDown('Ctrl+J', displayList)
g_keyboard.bindKeyDown('Ctrl+Y',
    function()
        
        -- say hello in chat
        if g_game.isOnline() then
            g_game.talk('hello')
        end
        
    end)

end

-- here are our event callbacks we registered in init()
function evt_attackingCreatureChanged()
    local attacking = g_game.getAttackingCreature()
    
    -- print creature name in our own console
    printConsole('Attacking: ' .. attacking:getName())
end

function evt_followingCreatureChanged()
    local following = g_game.getFollowingCreature()
    
    -- print creature name in our own console
    printConsole('Following: ' .. following:getName())
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
    local 
    = g_map.getSpectatorsInRangeEx(player:getPosition(), false, math.floor(dimension.width / 2), math.floor(dimension.width / 2) + 1, math.floor(dimension.height / 2), math.floor(dimension.height / 2) + 1)

    -- run the following code in protected mode (same as try/catch)
    local success, errmsg = pcall(function()
        -- (try)
        printConsole('Listing creatures:')
        for index, creature in pairs(spectators) do
            if checkValidCreatureForListing(creature) then
                printConsole('[#' .. tostring(index) .. '] ' .. creature:getName() .. ': Health % = ' .. tostring(creature:getHealthPercent()))
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

-- original mods are initialized and uninitialized automatically (and we "can" do the same)
-- but I left it intentionally to be initialized manually (there is no uninitializaiton yet)
-- this will give you the full control for now
init()