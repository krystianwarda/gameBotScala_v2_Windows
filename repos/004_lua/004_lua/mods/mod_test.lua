function init()

-- bind (register) change events for attacking and following creatures (they will be only run when you attack or follow something explicitly)

connect(g_game, {
    onAttackingCreatureChange = evt_attackingCreatureChanged,
    onFollowingCreatureChange = evt_followingCreatureChanged,
})

-- printConsole() prints to our console with the file name of this script
printConsole('Registered events')

-- register some keyboard shortcuts
g_keyboard.bindKeyDown('Ctrl+D', setBow)
g_keyboard.bindKeyDown('Ctrl+C', attackHMM)
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

function findEmptyContainerId()
    -- Directly calling the method from the g_game object
    local id = g_game.findEmptyContainerId()

    if id then
        printConsole("Empty container ID found: " .. id)
        return id
    else
        printConsole("No empty container ID found")
        return nil
    end
end


function getBackpackItems()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    -- Retrieve the backpack item from inventory slot 3
    local backpack = player:getInventoryItem(3)

    if not backpack then
        printConsole('No backpack found in slot 3')
        return
    end

    -- Assuming getContainerItems() and getContainerItem() are methods to access container's items
    local backpackItems = backpack:getContainerItems()
    if not backpackItems then
        printConsole('No items found in the backpack')
        return
    end

    -- Displaying items from the backpack
    for i, item in ipairs(backpackItems) do
        local containerItem = backpack:getContainerItem(i)
        if containerItem then
            printConsole("Item in backpack slot " .. i .. ": " .. containerItem:getId())
            -- You can serialize or process each item as needed
        end
    end
end

function attackHMM()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local hmmhId = 3198 -- ID of the item to be used (Ultimate Healing Rune)
    local foundItem = g_game.findPlayerItem(hmmhId, -1) -- -1 as the subtype if subtype is not specific

    local attackingCreature = g_game.getAttackingCreature()

    if foundItem and attackingCreature then
        -- Use the found item on the attacking creature
        g_game.useInventoryItemWith(hmmhId, attackingCreature, -1)
        printConsole("Used item with ID " .. hmmhId .. " on attacking creature")
    else
        if not foundItem then
            printConsole("Could not obtain item with ID " .. hmmhId)
        end
        if not attackingCreature then
            printConsole("No attacking creature found")
        end
    end
end



function healUH()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local uhId = 3200 -- ID of the item to be used (Ultimate Healing Rune) MF 2874 UH 3160 HMM 
    local foundItem = g_game.findPlayerItem(uhId, -1) -- -1 as the subtype if subtype is not specific

    if foundItem then
        -- Use the found item on the player
        g_game.useInventoryItemWith(uhId, player, -1)
        printConsole("Used item with ID " .. uhId .. " on player")
    else
        printConsole("Could not obtain item with ID " .. uhId)
    end
end


function setBow()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local amuletId = 3350 -- ID of the amulet
    local foundItem = g_game.findPlayerItem(amuletId, -1)  -- -1 as the subtype if subtype is not specific

    if foundItem then
        -- Move the found amulet to the desired inventory slot (assuming slot 6 for the amulet)
        local slotPosition = {x = 65535, y = 6, z = 0}  -- The inventory slot position for slot 6
        g_game.move(foundItem, slotPosition, foundItem:getCount())
        printConsole("Amulet moved to slot 6")
    else
        printConsole("Could not obtain item with ID " .. amuletId)
    end
end

function setAmulet()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local amuletId = 3081 -- ID of the amulet
    local foundItem = g_game.findPlayerItem(amuletId, -1)  -- -1 as the subtype if subtype is not specific

    if foundItem then
        -- Move the found amulet to the desired inventory slot (assuming slot 6 for the amulet)
        local slotPosition = {x = 65535, y = 2, z = 0}  -- The inventory slot position for slot 6
        g_game.move(foundItem, slotPosition, foundItem:getCount())
        printConsole("Amulet moved to slot 6")
    else
        printConsole("Could not obtain item with ID " .. amuletId)
    end
end


function setAmulet2()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    -- Retrieve the item object with ID 3350
    local item = g_game.findPlayerItem(3350, -1)  -- -1 as the subtype if subtype is not specific

    if item then
        -- Setting the retrieved item in inventory slot 6
        player:setInventoryItem(6, item)
        printConsole("Amulet set in slot 6")
    else
        printConsole("Could not obtain item with ID 3350")
    end
end



-- 3081 ss
function getItem()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    -- Fetching the item from inventory slot 2
    local item = player:getInventoryItem(6)
    if item then
        -- Displaying information about the item in slot 2
        -- You can replace 'getId' and 'getSubType' with other methods as needed
        printConsole("Item in slot 6: ID = " .. item:getId() .. ", SubType = " .. item:getSubType())
    else
        printConsole("No item found in slot 6")
    end

    -- Include additional code here if needed

    -- Error handling (if applicable)
    -- [Your existing error handling code]
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