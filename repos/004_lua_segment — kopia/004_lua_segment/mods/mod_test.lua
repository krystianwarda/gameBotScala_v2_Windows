function init()

-- bind (register) change events for attacking and following creatures (they will be only run when you attack or follow something explicitly)

connect(g_game, {
    onAttackingCreatureChange = evt_attackingCreatureChanged,
    onFollowingCreatureChange = evt_followingCreatureChanged,
})

-- printConsole() prints to our console with the file name of this script
printConsole('Registered events')

-- register some keyboard shortcuts
g_keyboard.bindKeyDown('Ctrl+D', getPos)
g_keyboard.bindKeyDown('Ctrl+A', replaceTreeWithBush)
g_keyboard.bindKeyDown('Ctrl+C', getTileInfoNextToPlayer)
g_keyboard.bindKeyDown('Ctrl+J', replaceTreeWithBushJ)
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

function getPos()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local playerXPos = player:getPosition().x
    local playerYPos = player:getPosition().y
    local playerZPos = player:getPosition().z
    printConsole("Player pos: " .. tostring(playerXPos) .. ", " .. tostring(playerYPos) .. ", " .. tostring(playerZPos)) 

end

function workingUsingonMyself()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local itemId = 3483 -- ID of the item to be used (Ultimate Healing Rune) MF 2874 UH 3160 fishing rod 3483 (sub1)
    local foundItem = g_game.findPlayerItem(itemId, -1) -- -1 as the subtype if subtype is not specific

    if foundItem then
        -- Use the found item on the player
        g_game.useWith(foundItem, player, -1)
        printConsole("Used item with ID " .. itemId .. " on player")
    else
        printConsole("Could not obtain item with ID " .. itemId) 
    end
end

function getTilesA()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end
    
    local tiles = g_map.getTiles(tonumber(player:getPosition().z))

    if #tiles > 0 then
        for i, tile in ipairs(tiles) do
            local topThing = tile:getTopMultiUseThing()
            --local tilePosition = tile
            -- local tilePosition = tile:getGround.getPosition()
            -- local tileWalkable = tile.isWalkable()
            -- local tilePathable = tile.isPathable()
            if topThing then
                printConsole("Tile Pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) ..  ", " .. tostring(tile:getPosition().z) .. ", Top thing at tile: " .. tostring(topThing:getId()))
                -- .. ", Walkable: " .. tostring(tileWalkable) .. ", Pathable: " .. tostring(tilePathable))
            else
                printConsole("No top thing found at tile " .. i)
            end
        end
    else
        printConsole("No tiles found at the current level")
    end
end

-- super
function getTilesC()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end
    
    local tiles = g_map.getTiles(tonumber(player:getPosition().z))

    if #tiles > 0 then
        for i, tile in ipairs(tiles) do
            local topThingList = tile:getItems()

            if topThingList and #topThingList > 0 then
                for j, topThing in ipairs(topThingList) do
                    printConsole("Tile Pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) .. ", " .. tostring(tile:getPosition().z) .. ", Top thing at tile: " .. tostring(topThing:getId()))
                    -- Additional properties of topThing can be printed here
                end
            else
                printConsole("No items found at tile " .. i)
            end
        end
    else
        printConsole("No tiles found at the current level")
    end
end


function getTilesInfo()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end
    
    local tiles = g_map.getTiles(tonumber(player:getPosition().z))

    if #tiles > 0 then
        for i, tile in ipairs(tiles) do
            local topThingList = tile:getItems()

            if topThingList and #topThingList > 0 then
                for j, topThing in ipairs(topThingList) do
                    printConsole("Tile Pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) .. ", " .. tostring(tile:getPosition().z) .. ", Top thing at tile: " .. tostring(topThing:getId()))
                    -- Additional properties of topThing can be printed here
                end
            else
                printConsole("No items found at tile " .. i)
            end
        end
    else
        printConsole("No tiles found at the current level")
    end
end

function getTilesJ()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end
    
    local tiles = g_map.getTiles(tonumber(player:getPosition().z))

    if #tiles > 0 then
        for i, tile in ipairs(tiles) do
            local topThing = tile:getTopUseThing()
            --local tilePosition = tile
            -- local tilePosition = tile:getGround.getPosition()
            -- local tileWalkable = tile.isWalkable()
            -- local tilePathable = tile.isPathable()
            if topThing then
                printConsole("Tile Pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) ..  ", " .. tostring(tile:getPosition().z) .. ", Top thing at tile: " .. tostring(topThing:getId()))
                -- .. ", Walkable: " .. tostring(tileWalkable) .. ", Pathable: " .. tostring(tilePathable))
            else
                printConsole("No top thing found at tile " .. i)
            end
        end
    else
        printConsole("No tiles found at the current level")
    end
end

function useFishingRod()
    math.randomseed(os.time())
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local itemId = 3483 -- ID of the fishing rod
    local foundItem = g_game.findPlayerItem(itemId, -1)

    local tiles = g_map.getTiles(tonumber(player:getPosition().z))

    if #tiles > 0 then
        local randomIndex = math.random(#tiles)
        local tile = tiles[randomIndex]
        if tile then
            local topThing = tile:getTopUseThing()
            if topThing and table.contains({618, 619, 620}, topThing:getId()) then
                printConsole("Using item with random tile: " .. tostring(topThing:getId()))
                g_game.useWith(foundItem, topThing, 1)
            else
                printConsole("No top thing found on the selected tile")
            end
        else
            printConsole("Failed to get a random tile")
        end
    else
        printConsole("No tiles found at the current level")
    end

end


function useManaPotion()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local uhId = 2874 -- ID of the item to be used (Ultimate Healing Rune) MF 2874 UH 3160 fishing rod 3483 (sub1)
    local foundItem = g_game.findPlayerItem(uhId, -1) -- -1 as the subtype if subtype is not specific

    if foundItem then
        -- Use the found item on the player
        g_game.useWith(foundItem, player, -1)
        printConsole("Used item with ID " .. uhId .. " on player")
    else
        printConsole("Could not obtain item with ID " .. uhId) 
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

    local uhId = 3160 -- ID of the item to be used (Ultimate Healing Rune) MF 2874 UH 3160 fishing rod 3483 (sub1)
    local foundItem = g_game.findPlayerItem(uhId, -1) -- -1 as the subtype if subtype is not specific

    if foundItem then
        -- Use the found item on the player
        g_game.useWith(foundItem, player, -1)
        printConsole("Used item with ID " .. uhId .. " on player")
    else
        printConsole("Could not obtain item with ID " .. uhId) 
    end
end

function replaceTreeWithBushJ()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end


    local tree_array = { 3614, 3615, 3616, 3617 }  -- Define this outside the loop
    local treeChange = 3682
    local targetPosition = {x = 32104, y = 32241, z = 7}
    local tiles = g_map.getTiles(player:getPosition().z)

    -- Custom function to find an item in tree_array
    local function isInTreeArray(id)
        for _, treeId in ipairs(tree_array) do
            if treeId == id then
                return true
            end
        end
        return false
    end

    for _, tile in ipairs(tiles) do
        if tile:getPosition().x == targetPosition.x and 
           tile:getPosition().y == targetPosition.y and 
           tile:getPosition().z == targetPosition.z then

            printConsole('The tile has been found.')
            local itemList = tile:getItems()

            if itemList and #itemList > 0 then
                for j, item in ipairs(itemList) do
                    printConsole("Tile Pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) .. ", " .. tostring(tile:getPosition().z) .. ", Top thing at tile: " .. tostring(item:getId()))
                    if isInTreeArray(item:getId()) then
                        item:setId(treeChange)
                    end
                 end
            end

            printConsole('Breaking loop.')
            break
        end
    end
end


function replaceTreeWithBush()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local treeChange = 3682
    local targetPosition = {x = 32104, y = 32241, z = 7}
    local tiles = g_map.getTiles(player:getPosition().z)

    for _, tile in ipairs(tiles) do
        if tile:getPosition().x == targetPosition.x and 
           tile:getPosition().y == targetPosition.y and 
           tile:getPosition().z == targetPosition.z then

            printConsole('The tile has been found.')
            local itemList = tile:getItems()

            printConsole('Before loop.')
            for j, item in ipairs(itemList) do
                printConsole("Tile Pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) .. ", " .. tostring(tile:getPosition().z) .. ", Top thing at tile: " .. tostring(topThing:getId()))
                tree_array = { 3614, 3615, 3616, 3617 }
                printConsole('Looking for tree.')
                if table.find(tree_array, item:getId()) then
                    printConsole('Tree found.')
                    item:setId(treeChange)
                    printConsole('Tree changed to bush.')
                end
                end



            printConsole('Breaking loop.')
            break
        end
    end
end
function getTileInfoNextToPlayer()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    -- Use the player's current position
    local targetPosition = player:getPosition()
    local tiles = g_map.getTiles(targetPosition.z)

    for _, tile in ipairs(tiles) do
        if tile:getPosition().x == targetPosition.x+1 and 
           tile:getPosition().y == targetPosition.y and 
           tile:getPosition().z == targetPosition.z then

            printConsole('The tile has been found.')
            local topThingList = tile:getItems()

            if topThingList and #topThingList > 0 then
                for j, topThing in ipairs(topThingList) do
                    local priority = topThing:getStackPriority()
                    printConsole("Tile stackPriority: " .. tostring(priority))
                    printConsole("Tile Pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) .. ", " .. tostring(tile:getPosition().z) .. ", Top thing at tile: " .. tostring(topThing:getId()))
                end
            end

            printConsole('Breaking loop.')
            break
        end
    end
end


function reorderItemsOnTileC()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local targetPosition = {x = 32104, y = 32241, z = 7}
    local tiles = g_map.getTiles(player:getPosition().z)

    for _, tile in ipairs(tiles) do
        if tile:getPosition().x == targetPosition.x and 
           tile:getPosition().y == targetPosition.y and 
           tile:getPosition().z == targetPosition.z then

            printConsole('The tile has been found.')
            local topThingList = tile:getItems()

            if topThingList and #topThingList > 0 then
                for j, topThing in ipairs(topThingList) do

                    -- Calculate the correct stack position for the item
                    local priority = topThing:getStackPriority()
                    printConsole("Tile stackPriority: " .. tostring(priority))
                    printConsole("Tile Pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) .. ", " .. tostring(tile:getPosition().z) .. ", Top thing at tile: " .. tostring(topThing:getId()))
                    -- 
                 end
            end


            printConsole('Breaking loop.')
            break
        end
    end
end

function reorderItemsOnTileJ()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local targetPosition = {x = 32103, y = 32240, z = 7}
    local tiles = g_map.getTiles(player:getPosition().z)

    for _, tile in ipairs(tiles) do
        if tile:getPosition().x == targetPosition.x and 
           tile:getPosition().y == targetPosition.y and 
           tile:getPosition().z == targetPosition.z then

            printConsole('The tile has been found.')
            local topThingList = tile:getItems()

            if topThingList and #topThingList > 0 then
                for j, topThing in ipairs(topThingList) do

                    -- Calculate the correct stack position for the item
                    local priority = topThing:getStackPriority()
                    printConsole("Tile stackPriority: " .. tostring(priority))
                    printConsole("Tile Pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) .. ", " .. tostring(tile:getPosition().z) .. ", Top thing at tile: " .. tostring(topThing:getId()))
                end
            end

            printConsole('Breaking loop.')
            break
        end
    end
end




-- super
function getTilesC()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end
    
    local tiles = g_map.getTiles(tonumber(player:getPosition().z))

    if #tiles > 0 then
        for i, tile in ipairs(tiles) do
            local topThingList = tile:getItems()

            if topThingList and #topThingList > 0 then
                for j, topThing in ipairs(topThingList) do
                    printConsole("Tile Pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) .. ", " .. tostring(tile:getPosition().z) .. ", Top thing at tile: " .. tostring(topThing:getId()))
                    -- Additional properties of topThing can be printed here
                end
            else
                printConsole("No items found at tile " .. i)
            end
        end
    else
        printConsole("No tiles found at the current level")
    end
end



-- Merged function
function displaceThingOnTheGround()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end
    
    local tiles = g_map.getTiles(tonumber(player:getPosition().z))

    if #tiles > 0 then
        for i, tile in ipairs(tiles) do
            local topThingList = tile:getItems()

            if topThingList and #topThingList > 0 then
                for j, topThing in ipairs(topThingList) do
                    if tostring(topThing:getId()) == "3277" then
                        local currentPosition = tile:getPosition()
                        local newPosition = {x = currentPosition.x, y = currentPosition.y + 1, z = currentPosition.z}
                        g_game.move(topThing, newPosition, topThing:getCount())
                        printConsole("Moved item with ID 3277 to position: " .. tostring(newPosition.x) .. ", " .. tostring(newPosition.y) .. ", " .. tostring(newPosition.z))
                    else
                        printConsole("Tile Pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) .. ", " .. tostring(tile:getPosition().z) .. ", Top thing at tile: " .. tostring(topThing:getId()))
                    end
                end
            else
                printConsole("No items found at tile " .. tostring(i))
            end
        end
    else
        printConsole("No tiles found at the current level")
    end
end

-- Merged function
function moveThingFromTheGroundToEquipment()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end
    
    local tiles = g_map.getTiles(tonumber(player:getPosition().z))

    if #tiles > 0 then
        for i, tile in ipairs(tiles) do
            local topThingList = tile:getItems()

            if topThingList and #topThingList > 0 then
                for j, topThing in ipairs(topThingList) do
                    if tostring(topThing:getId()) == "3277" then
                        local currentPosition = tile:getPosition()
                        local slotPosition = {x = 65535, y = 6, z = 0}
                        g_game.move(topThing, slotPosition, topThing:getCount())
                        printConsole("Moved item with ID 3277 to position: " .. tostring(newPosition.x) .. ", " .. tostring(newPosition.y) .. ", " .. tostring(newPosition.z))
                    else
                        printConsole("Tile Pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) .. ", " .. tostring(tile:getPosition().z) .. ", Top thing at tile: " .. tostring(topThing:getId()))
                    end
                end
            else
                printConsole("No items found at tile " .. tostring(i))
            end
        end
    else
        printConsole("No tiles found at the current level")
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
    local item = g_game.findPlayerItem(3350, -1)  

    if item then
        -- Setting the retrieved item in inventory slot 6
        player:setInventoryItem(6, item)
        printConsole("Amulet set in slot 6")
    else
        printConsole("Could not obtain item with ID 3350")
    end
end


function getContainerLoc()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local itemId = 3483 -- Specify the item ID you want to find
    local foundItem = g_game.findPlayerItem(itemId, -1)
    if foundItem then
        local position = foundItem:getPosition() -- Assuming getPosition returns the position of the item

        if position.x == 65535 then -- Item is in a container
            local containerId = position.y
            local slot = position.z
            printConsole("Found item: ID = " .. tostring(foundItem:getId()) .. 
                         ", Count: " .. tostring(foundItem:getCount()) .. 
                         ", SubType = " .. tostring(foundItem:getSubType()) ..
                         ", Container ID: " .. tostring(containerId) ..
                         ", Slot: " .. tostring(slot))
        else
            -- Item is in the game world
            printConsole("Found item: ID = " .. tostring(foundItem:getId()) .. 
                         ", Count: " .. tostring(foundItem:getCount()) .. 
                         ", SubType = " .. tostring(foundItem:getSubType()) ..
                         ", World Position: (" .. tostring(position.x) .. ", " .. tostring(position.y) .. ", " .. tostring(position.z) .. ")")
        end
    else
        printConsole("Item with ID " .. tostring(itemId) .. " not found")
    end
end



function getItemLoc()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local itemId = 3483 -- Specify the item ID you want to find
    -- Finding the item with the specified ID
    local foundItem = g_game.findPlayerItem(itemId, -1)
    if foundItem then
        local position = foundItem:getPosition() -- Assuming getPosition returns the position of the item

        -- Displaying information about the found item
        printConsole("Found item: ID = " .. tostring(foundItem:getId()) .. 
                     ", Count: " .. tostring(foundItem:getCount()) .. 
                     ", SubType = " .. tostring(foundItem:getSubType()) ..
                     ", Position: (" .. tostring(position.x) .. ", " .. tostring(position.y) .. ", " .. tostring(position.z) .. ")")
    else
        printConsole("Item with ID " .. tostring(itemId) .. " not found")
    end
end



-- 3081 ss
function getItemCount()
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
        printConsole("!Item in slot 6: ID = " .. item:getId() .. ", Count: " .. item:getCount() .. ", SubType = " .. item:getSubType())
    else
        printConsole("No item found in slot 6")
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



function testGWindow()
    if g_window then
        printConsole("g_window exists.")
    else
        printConsole("g_window does not exist.")
    end
end

function testGetMousePosition()
    if g_window and g_window.getMousePosition then
        local mousePos = g_window.getMousePosition()
        if mousePos then
            printConsole("Mouse position: x=" .. tostring(mousePos.x) .. ", y=" .. tostring(mousePos.y))
        else
            printConsole("Failed to get mouse position.")
        end
    else
        printConsole("g_window or g_window.getMousePosition does not exist.")
    end
end

function testGApp()
    if g_app then
        printConsole("g_app exists.")

        -- Testing a method of g_app, like getName() or getVersion()
        local appName = g_app.getName and g_app:getName() or "Method getName() does not exist"
        local appVersion = g_app.getVersion and g_app:getVersion() or "Method getVersion() does not exist"

        printConsole("Application Name: " .. appName)
        printConsole("Application Version: " .. appVersion)
    else
        printConsole("g_app does not exist.")
    end
end

function getItemScreenPosition(itemId)
    printConsole("Getting screen position for item ID: " .. tostring(itemId))

    local mousePos = g_window.getMousePosition()
    printConsole("Current mouse position: x=" .. tostring(mousePos.x) .. ", y=" .. tostring(mousePos.y))

    local gamePosition = UIMap:getPosition(mousePos)
    printConsole("Game position from mouse position: " .. tostring(gamePosition.x) .. ", " .. tostring(gamePosition.y))
    
    local screenPosition = convertGamePositionToScreenPosition(gamePosition)
    printConsole("Converted screen position: x=" .. tostring(screenPosition.x) .. ", y=" .. tostring(screenPosition.y))

    return screenPosition
end

function getLocA()
    print("Checking if game is online")
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    print("Getting local player")
    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    print("Getting inventory item from slot 6")
    local itemPtr = player:getInventoryItem(6)
    if itemPtr then
        local itemId = itemPtr:getId()
        print("Found item in slot 6 with ID:", itemId)

        local x, y = getItemScreenPosition(itemId)
        if x and y then
            printConsole("Item position: x=" .. tostring(x) .. ", y=" .. tostring(y))
        else
            printConsole("UIItem for item ID " .. tostring(itemId) .. " not found")
        end
    else
        printConsole("No item found in slot 6")
    end
end




function getLocC()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    -- Retrieve the item object with ID 3483
    local item = g_game.findPlayerItem(3483, -1)
    if item then
        -- Assuming you can get the UIItem instance from the ItemPtr
        local uiItem = item:getUIItem() -- This method needs to exist or be created
        if uiItem then
            local x = uiItem:getX()
            local y = uiItem:getY()
            printConsole("Item position: x=" .. tostring(x) .. ", y=" .. tostring(y))
        else
            printConsole("UIItem instance not found for the item")
        end
    else
        printConsole("No item with ID 3483 found")
    end
end



function getLocJ()
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
        printConsole("getSize: " .. tostring(item:getSize()))
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