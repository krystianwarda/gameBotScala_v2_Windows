function buttonFunctions()
    local message = '{ "status": "Button functions activated." }'
    printConsole(message)
    -- register some keyboard shortcuts
    g_keyboard.bindKeyDown('Alt+P', spyfloor_up)
    g_keyboard.bindKeyDown('Alt+L', spyfloor_down)
    g_keyboard.bindKeyDown('Alt+O', spyfloor_reset)
    g_keyboard.bindKeyDown('Alt+T', tileUnderCursor)
    g_keyboard.bindKeyDown('Alt+H', whatIsinMyRightHand)
    g_keyboard.bindKeyDown('Ctrl+1', testHotkey1)
    g_keyboard.bindKeyDown('Ctrl+2', testHotkey2)
    g_keyboard.bindKeyDown('Ctrl+2', testHotkey3)
    g_keyboard.bindKeyDown('Ctrl+2', testHotkey4)
end


function useOnYourself(arg)
    local player = g_game.getLocalPlayer()
    local itemId = arg.data.itemInfo.itemId
    local itemSubType
    
    -- Check if itemSubType exists in the JSON
    if arg.data.itemInfo.itemSubType ~= nil then
        itemSubType = arg.data.itemInfo.itemSubType
    else
        itemSubType = -1 -- Default value if itemSubType doesn't exist
    end
    
    local foundItem = g_game.findPlayerItem(itemId, itemSubType) 

    if foundItem then
        -- Use the found item on the player
        g_game.useWith(foundItem, player, itemSubType)
        printConsole("Used item with ID " .. itemId .. " on player")
    else
        printConsole("Could not obtain item with ID " .. itemId) 
    end
end




-- HOTKEYS
function testHotkey1()
    local tempHotkey = modules.game_hotkeys.onHotkeyTextChange(self:getText())
    printConsole("Hotkeys: " .. tostring(tempHotkey))
end

function getHotkeysForF1ToF5()
  -- Assuming that 'hotkeyList' is the table where all hotkeys are stored
  local hotkeys = hotkeyList or {}
  local f1ToF5Hotkeys = {}

  -- Key combinations for F1 to F5
  local functionKeys = {"F1", "F2", "F3", "F4", "F5"}
  for _, fKey in pairs(functionKeys) do
    local hotkey = hotkeys[fKey]
    if hotkey then
      -- Add the hotkey settings for F1 to F5 to the table
      f1ToF5Hotkeys[fKey] = hotkey
    end
  end

  return f1ToF5Hotkeys
end

-- To test and print the hotkeys for F1 to F5
function testHotkey2()
  local f1ToF5Hotkeys = getHotkeysForF1ToF5()
  for fKey, settings in pairs(f1ToF5Hotkeys) do
    printConsole(fKey .. ": " .. tostring(settings))
  end
end


function testHotkey3()
  unload() -- Unbinds all current hotkeys and clears UI
  load()   -- Loads hotkeys from the configuration file again
end


function testHotkey4()
  if not configValueChanged then
    return
  end
  
  -- Your existing save logic here
  hotkeyConfigs[currentConfig]:save()
  g_settings.save()
  
  -- Add a call to reload hotkeys after saving
  reload()
end


-- MAGIC WALL
-- "326833169007":{"isClickable":true,"isPathable":true,"isWalkable":false,"items":{"1":102,"2":2128}},
-- "326833169007":{"isClickable":true,"isPathable":true,"isWalkable":true,"items":{"1":102}},

-- depo [unknown source]: Tile position x = 32678, y = 31687, Item ID = 3498
-- LADER [unknown source]: Tile position x = 32682, y = 31687, Item ID = 1948
-- LADER DOWN [unknown source]: Tile position x = 32682, y = 31685, Item ID = 433

function checkPZ1()
    local tempTile = modules.game_interface.gameMapPanel:getTile(modules.game_interface.gameMapPanel.mousePos)
    if tempTile then
        -- Assuming getFlags is available and returns the raw flag value for debugging
        local tileFlags = tempTile:getFlags()
        printConsole("Tile flags: " .. tostring(tileFlags))

        local TILESTATE_PROTECTIONZONE = 1 -- Update this with the correct value from C++
        local isProtectionZone = tempTile:hasFlag(TILESTATE_PROTECTIONZONE)
        printConsole("Tile is in protection zone: " .. tostring(isProtectionZone))
    else
        printConsole("No tile found at mouse position.")
    end
end


function checkPZ2()
    local tempTile = modules.game_interface.gameMapPanel:getTile(modules.game_interface.gameMapPanel.mousePos)
    if tempTile then
        local tileFlags = tempTile:getFlags()
        printConsole("Tile flags: " .. tileFlags) -- Print all flags for debugging
        local TILESTATE_PROTECTIONZONE = 1 -- Ensure this matches the C++ definition
        local isProtectionZone = tempTile:hasFlag(TILESTATE_PROTECTIONZONE)
        printConsole("Tile is in protection zone: " .. tostring(isProtectionZone))
    else
        printConsole("No tile found at mouse position.")
    end
end

function checkPZ3()
    local tempTile = modules.game_interface.gameMapPanel:getTile(modules.game_interface.gameMapPanel.mousePos)
    if tempTile then
        local tileFlags = tempTile:getFlags()
        printConsole("Tile flags: " .. tileFlags) -- This will print the flags as a number
        local TILESTATE_PROTECTIONZONE = 1 -- Double-check this value!
        local isProtectionZone = tempTile:hasFlag(TILESTATE_PROTECTIONZONE)
        printConsole("Tile is in protection zone: " .. tostring(isProtectionZone))
    else
        printConsole("No tile found at mouse position.")
    end
end


function spyLevelsOld()        
    local player = g_game.getLocalPlayer()
    local dimension = modules.game_interface.getMapPanel():getVisibleDimension()
    printConsole("Map dimension: width=" .. tostring(dimension.width) .. ", height=" .. tostring(dimension.height))
    local spectators = g_map.getSpectatorsInRangeEx(player:getPosition(), true, math.floor(dimension.width / 2 + 2), math.floor(dimension.width / 2 + 2), math.floor(dimension.height / 2 + 2), math.floor(dimension.height / 2 + 2))
    for _, creature in ipairs(spectators) do
        -- Collecting creature details
        local Name = creature:getName()
        local Id = creature:getId()
        local HealthPercent = creature:getHealthPercent()
        local PositionX = creature:getPosition().x
        local PositionY = creature:getPosition().y
        local PositionZ = creature:getPosition().z
        local IsNpc = creature:isNpc()
        local IsPlayer = creature:isPlayer()
        local IsMonster = creature:isMonster()
        
        -- Printing creature details in a single line
        printConsole(string.format("Name: %s, ID: %s, HealthPercent: %s%%, Position: [%s, %s, %s], NPC: %s, Player: %s, Monster: %s",
            Name, Id, HealthPercent, PositionX, PositionY, PositionZ, tostring(IsNpc), tostring(IsPlayer), tostring(IsMonster)))
    end
end


function tileUnderCursor()
    local tempTile = modules.game_interface.gameMapPanel:getTile(modules.game_interface.gameMapPanel.mousePos)

    -- Assuming there's a function to get the position
    local position = tempTile:getPosition()

    -- Extracting the x and y coordinates
    local x = position.x
    local y = position.y

    -- Getting the list of items on the tile
    local topThingList = tempTile:getItems()
    if topThingList and #topThingList > 0 then
        for j, topThing in ipairs(topThingList) do
            local itemId = topThing:getId()
            local itemName = topThing:getName() -- Assuming there's a getName function to get the name of the item

            -- Print all details in a single line
            printConsole(string.format("Tile position x = %s, y = %s, Item ID = %s", x, y, itemId))
        end
    else
        -- If no items are found on the tile, print tile position only
        printConsole("Tile position x = " .. x .. " y = " .. y .. " - No items found on this tile.")
    end
end


function spyfloor_up()
    local player = g_game.getLocalPlayer()
    local levelUp = player:getPosition().z - 1
    modules.game_interface.getMapPanel():lockVisibleFloor(levelUp)
end

function spyfloor_down()
    local player = g_game.getLocalPlayer()
    local levelDown = player:getPosition().z + 1  
    modules.game_interface.getMapPanel():lockVisibleFloor(levelDown)
end

function spyfloor_reset()
    modules.game_interface.getMapPanel():unlockVisibleFloor()
end


--[unknown source]: Right Panel: userdata: 0x15d55558
function containerTest2_old1()
    local rightPanel = modules.game_interface.getRightPanel()
    printConsole('Right Panel: ' .. tostring(rightPanel)) -- Adjusted the text to match the variable name
end

-- good
function containerTest2_old2()
    local rightPanel = modules.game_interface.getRightPanel()
    local rightPanelRect = rightPanel:getRect()
    local offset = 30
    printConsole("Right Panel x=" .. tostring(rightPanelRect.x) .. ", y=" .. tostring(rightPanelRect.y + offset))
    printConsole('ParentRect width ' .. tostring(rightPanelRect.width) .. ' height ' .. tostring(rightPanelRect.height))
    
    local rightPanelChildrenCount = rightPanel:getChildCount()
    printConsole('Right Panel Count: ' .. tostring(rightPanelChildrenCount))

    -- Iterate over all children of the right panel
    for i = 1, rightPanelChildrenCount do
        local child = rightPanel:getChildByIndex(i)

        local childRect = child:getRect()
        -- Check if childRect.x is not equal to 0 before displaying
        if childRect.x ~= 0 then
            printConsole("Child " .. i .. " Rect x=" .. tostring(childRect.x) .. ", y=" .. tostring(childRect.y + offset) .. ', width ' .. tostring(childRect.width) .. ' height ' .. tostring(childRect.height))
        end
    end
end

function containerTest2_old3()
    local rightPanel = modules.game_interface.getRightPanel()
    local rightPanelRect = rightPanel:getRect()
    local offset = 30
    printConsole("Right Panel x=" .. tostring(rightPanelRect.x) .. ", y=" .. tostring(rightPanelRect.y + offset))
    printConsole('ParentRect width ' .. tostring(rightPanelRect.width) .. ' height ' .. tostring(rightPanelRect.height))
    
    local rightPanelChildrenCount = rightPanel:getChildCount()
    printConsole('Right Panel Count: ' .. tostring(rightPanelChildrenCount))

    -- Iterate over all children of the right panel
    for i = 1, rightPanelChildrenCount do
        local child = rightPanel:getChildByIndex(i)
        local childRect = child:getRect()
        -- Check if childRect.x is not equal to 0 before displaying
        if childRect.x ~= 0 then
            printConsole("Child " .. i .. " Rect x=" .. tostring(childRect.x) .. ", y=" .. tostring(childRect.y + offset) .. ', width ' .. tostring(childRect.width) .. ' height ' .. tostring(childRect.height))
            local childChildrenCount = child:getChildCount()
            printConsole('Child Panel Count: ' .. tostring(childChildrenCount))

            -- Nested loop to iterate over the children of the child
            for j = 1, childChildrenCount do
                local subChild = child:getChildByIndex(j)
                local subChildRect = subChild:getRect()
                -- Check if subChildRect.x is not equal to 0 before displaying
                if subChildRect.x ~= 0 then
                    printConsole("Sub-Child " .. j .. " of Child " .. i .. " Rect x=" .. tostring(subChildRect.x) .. ", y=" .. tostring(subChildRect.y + offset) .. ', width ' .. tostring(subChildRect.width) .. ' height ' .. tostring(subChildRect.height))
                end
            end
        end
    end
end

function containerTest2()
    local rightPanel = modules.game_interface.getRightPanel()
    local rightPanelRect = rightPanel:getRect()
    local offset = 30
    printConsole("Right Panel x=" .. tostring(rightPanelRect.x) .. ", y=" .. tostring(rightPanelRect.y + offset))
    printConsole('ParentRect width ' .. tostring(rightPanelRect.width) .. ' height ' .. tostring(rightPanelRect.height))
    
    local rightPanelChildrenCount = rightPanel:getChildCount()
    printConsole('Right Panel Count: ' .. tostring(rightPanelChildrenCount))

    -- Iterate over all children of the right panel
    for i = 1, rightPanelChildrenCount do
        local child = rightPanel:getChildByIndex(i)
        local childRect = child:getRect()
        -- Check if width and height of child are greater than 20
        if childRect.x ~= 0 and childRect.width > 20 and childRect.height > 20 then
            printConsole("Child " .. i .. " Id: " .. tostring(child:getId()) .. " Rect x=" .. tostring(childRect.x) .. ", y=" .. tostring(childRect.y + offset) .. ', width ' .. tostring(childRect.width) .. ' height ' .. tostring(childRect.height))
            local childChildrenCount = child:getChildCount()

            -- Nested loop to iterate over the children of the child
            for j = 1, childChildrenCount do
                local subChild = child:getChildByIndex(j)
                local subChildRect = subChild:getRect()
                -- Check if width and height of subChild are greater than 20
                if subChildRect.x ~= 0 and subChildRect.width > 20 and subChildRect.height > 20 then
                    printConsole("Sub-Child " .. j .. " of Child " .. i .. " Id: " .. tostring(subChild:getId()) .. " Rect x=" .. tostring(subChildRect.x) .. ", y=" .. tostring(subChildRect.y + offset) .. ', width ' .. tostring(subChildRect.width) .. ' height ' .. tostring(subChildRect.height))
                    
                    local subChildChildrenCount = subChild:getChildCount()
                    -- Nested loop for sub-sub-children
                    for k = 1, subChildChildrenCount do
                        local subSubChild = subChild:getChildByIndex(k)
                        local subSubChildRect = subSubChild:getRect()
                        -- Check if width and height of subSubChild are greater than 20
                        if subSubChildRect.x ~= 0 and subSubChildRect.width > 20 and subSubChildRect.height > 20 then
                            printConsole("Sub-Sub-Child " .. k .. " of Sub-Child " .. j .. " of Child " .. i .. " Id: " .. tostring(subSubChild:getId()) .. " Rect x=" .. tostring(subSubChildRect.x) .. ", y=" .. tostring(subSubChildRect.y + offset) .. ', width ' .. tostring(subSubChildRect.width) .. ' height ' .. tostring(subSubChildRect.height))
                            
                            local subSubChildChildrenCount = subSubChild:getChildCount()
                            -- Nested loop for sub-sub-sub-children
                            for l = 1, subSubChildChildrenCount do
                                local subSubSubChild = subSubChild:getChildByIndex(l)
                                local subSubSubChildRect = subSubSubChild:getRect()
                                -- Check if width and height of subSubSubChild are greater than 20
                                if subSubSubChildRect.x ~= 0 and subSubSubChildRect.width > 20 and subSubSubChildRect.height > 20 then
                                    printConsole("Sub-Sub-Sub-Child " .. l .. " of Sub-Sub-Child " .. k .. " of Sub-Child " .. j .. " of Child " .. i .. " Id: " .. tostring(subSubSubChild:getId()) .. " Rect x=" .. tostring(subSubSubChildRect.x) .. ", y=" .. tostring(subSubSubChildRect.y + offset) .. ', width ' .. tostring(subSubSubChildRect.width) .. ' height ' .. tostring(subSubSubChildRect.height))
                                end
                            end
                        end
                    end
                end
            end
        end
    end
end

function calculateInventoryPositions()
    local inventoryData = {}
    local offset = 30

    local rightPanel = modules.game_interface.getRightPanel()
    local rightPanelChildrenCount = rightPanel:getChildCount()

    -- Iterate over all children of the right panel
    for i = 1, rightPanelChildrenCount do
        local child = rightPanel:getChildByIndex(i)
        local childId = child:getId()
        local childRect = child:getRect()

        -- Check if child meets the specified conditions
        if childRect.x ~= 0 and childRect.width > 20 and childRect.height > 20 then
            local containerOrEqWindowData = {}
            local childChildrenCount = child:getChildCount()

            -- Iterate over the sub-children (contentsPanel assumed) of the child
            for j = 1, childChildrenCount do
                local subChild = child:getChildByIndex(j)
                local subChildId = subChild:getId()
                local subChildRect = subChild:getRect()

                -- Check if subChild meets the specified conditions
                if subChildRect.x ~= 0 and subChildRect.width > 20 and subChildRect.height > 20 then
                    local slotsData = {}
                    local subChildChildrenCount = subChild:getChildCount()

                    -- Iterate over the sub-sub-children (slots) of the subChild
                    for k = 1, subChildChildrenCount do
                        local subSubChild = subChild:getChildByIndex(k)
                        local subSubChildRect = subSubChild:getRect()

                        -- Check if subSubChild meets the specified conditions
                        if subSubChildRect.x ~= 0 and subSubChildRect.width > 20 and subSubChildRect.height > 20 then
                            -- Calculate slot's center position and adjust with offset for y
                            local slotPosition = {
                                x = subSubChildRect.x + subSubChildRect.width / 2,
                                y = subSubChildRect.y + subSubChildRect.height / 2 + offset
                            }
                            slotsData["slot" .. k] = slotPosition
                        end
                    end

                    if next(slotsData) ~= nil then -- Check if slotsData is not empty
                        containerOrEqWindowData[subChildId] = slotsData
                    end
                end
            end
            if next(containerOrEqWindowData) ~= nil then -- Check if containerOrEqWindowData is not empty
                -- Check if childId starts with "container"
                if string.sub(childId, 1, 9) == "container" then
                    local num = string.match(childId, "%d+")
                    -- Directly construct the childId using "container" and the number
                    -- This avoids issues with spaces or incomplete names
                    childId = "container" .. num
                end
                -- Assuming each child of the right panel is a unique container or EqWindow
                inventoryData[childId] = containerOrEqWindowData
            end
        end
    end

    return inventoryData
end




function containerTest3()
    for _, container in pairs(g_game.getContainers()) do -- Added 'do' keyword
    local containerPos = container.getRect()
        printConsole('Container: ' .. tostring(containerPos.x)) 
    end
end

-- [unknown source]: Item: 3503 (parcel)
-- [unknown source]: Item: 3503 (parcel)
-- [unknown source]: Item: 2853 (bag)
function containerTest4old()
    for _, container in pairs(g_game.getContainers()) do -- Added 'do' keyword
        local tempItem = container:getContainerItem():getId()
        printConsole('Item: ' .. tostring(tempItem))
    end
end


function containerTest4()
    for _, container in pairs(g_game.getContainers()) do
        local containerName = container:getName()
        local containerCapacity = container:getCapacity()
        local containerSize = container:getSize()
        local containerId = container:getId()
        local hasParent = container:hasParent()

        printConsole('Container Name: ' .. tostring(containerName))
        printConsole('Container ID: ' .. tostring(containerId)) 
        printConsole('Capacity: ' .. tostring(containerCapacity))
        printConsole('Size: ' .. tostring(containerSize)) 
        printConsole('Has Parent: ' .. tostring(hasParent))

        local items = container:getItems()
        if #items > 0 then
            for slot, item in ipairs(items) do
                printConsole('Slot: ' .. tostring(slot))
                local itemId = item:getId()
                printConsole('itemId: ' .. tostring(itemId))
                local itemCount = item:getCount()
                printConsole('itemCount: ' .. tostring(itemCount))
                local itemSubType = item:getSubType()
                printConsole('itemSubType: ' .. tostring(itemSubType))
                local itemPosition = item:getPosition()
                printConsole("Position: (" .. tostring(itemPosition.x) .. ", " .. tostring(itemPosition.y) .. ", " .. tostring(itemPosition.z) .. ")")
                
            end
        else
            printConsole('No items in this container.')
        end
    end
end

function getOpenContainersData()
    local allContainers = {}

    for _, container in pairs(g_game.getContainers()) do
        local containerKey = "container" .. tostring(container:getId())
        local items = container:getItems()
        local containerData = {
            name = container:getName(),
            capacity = container:getCapacity(),
            itemsCount = #items,
            hasParent = container:hasParent(),
            items = {},
            freeSpace = container:getCapacity() - #items,
        }

        
        if #items > 0 then
            for slot, item in ipairs(items) do
                -- Construct item data for each slot
                local itemData = {
                    itemId = item:getId(),
                    itemCount = item:getCount(),
                    itemSubType = item:getSubType(),
                    posX = item:getPosition().x,
                    posY = item:getPosition().y,
                    posZ = item:getPosition().z
                }
                -- Use 'slotX' format for keys
                containerData.items["slot" .. tostring(slot)] = itemData
            end
        else
            -- Use a special string to indicate an empty container
            containerData.items = "empty"
        end

        -- Assign constructed container data to the allContainers table
        allContainers[containerKey] = containerData
    end

    -- Return the constructed Lua table
    return allContainers
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

function sayText(arg)
    g_game.talk(arg.data.text)
end



function moveBlankRuneBack(arg)
    local player = g_game.getLocalPlayer()
    local itemInHand = player:getInventoryItem(6)
    local backPositionTemp = {x=arg.data.backPosition.x, y=arg.data.backPosition.y, z=arg.data.backPosition.z}
    g_game.move(itemInHand, backPositionTemp, itemInHand:getCount())
end

function setBlankRune()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local blankId = 3147
    local foundItem = g_game.findPlayerItem(blankId, -1)

    if foundItem then
        local backpack = player:getInventoryItem(3)
        local slotPosition = {x = 65535, y = 6, z = 0}  
        g_game.move(foundItem, slotPosition, foundItem:getCount())
    else
        printConsole("Could not obtain item with ID " .. amuletId)
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

-- function blank_rune
-- blank rune id 3147


function calculateInventoryPanelLocTest()

    local jsonPoints = {}

    -- Get display width and height
    local displayWidth = g_window.getDisplayWidth()
    local displayHeight = g_window.getDisplayHeight()

    -- Calculate GameWindow position
    local windowPosX = g_window.getX()
    local windowPosY = g_window.getY()
    local gameWindowPoint = { x = windowPosX, y = windowPosY }
    
    local tempMapPanelChildren = modules.game_interface.gameMapPanel:getRect()
    local mapPanelPoint = { x = tempMapPanelChildren.x, y = tempMapPanelChildren.y }



    -- Define slot names and their IDs
    local slotNames = {
        "helmet", "amulet", "backpack", "armor", 
        "hand_right", "hand_left", "legs", "boots", 
        "ring", "arrows"
    }

    -- Iterate through each slot, calculate and store its position
    for i, slotName in ipairs(slotNames) do
        local slotId = "slot" .. i

        local slotChild = modules.game_inventory.inventoryWindow:recursiveGetChildById(slotId):getPosition()

        if slotChild then

            -- Calculate Slot position relative to GameWindow

            local slotPoint = { 
                x = gameWindowPoint.x + mapPanelPoint.x + slotChild.x, 
                y = gameWindowPoint.y + mapPanelPoint.y + slotChild.y + 35
            }
            -- Store the position with the named slot key
            jsonPoints[slotName] = { x = slotPoint.x, y = slotPoint.y }
        end
    end

    printConsole(tableToString(jsonPoints))

end


function calculateInventoryPanelLocOld()

    local jsonPoints = {}
    -- Get display width and height
    local displayWidth = g_window.getDisplayWidth()
    local displayHeight = g_window.getDisplayHeight()

    -- Calculate GameWindow position
    local windowPosX = g_window.getX()
    local windowPosY = g_window.getY()
    local gameWindowPoint = { x = windowPosX, y = windowPosY }
    
    local tempMapPanelChildren = modules.game_interface.gameMapPanel:getRect()
    local mapPanelPoint = { x = tempMapPanelChildren.x, y = tempMapPanelChildren.y }



    -- Define slot names and their IDs
    local slotNames = {
        "helmet", "amulet", "backpack", "armor", 
        "hand_right", "hand_left", "legs", "boots", 
        "ring", "arrows"
    }

    -- Iterate through each slot, calculate and store its position
    for i, slotName in ipairs(slotNames) do
        local slotId = "slot" .. i

        local slotChild = modules.game_inventory.inventoryWindow:recursiveGetChildById(slotId):getPosition()

        if slotChild then

            -- Calculate Slot position relative to GameWindow

            local slotPoint = { 
                x = gameWindowPoint.x + mapPanelPoint.x + slotChild.x + 10, 
                y = gameWindowPoint.y + mapPanelPoint.y + slotChild.y - 35 + 10
            }
            -- Store the position with the named slot key
            jsonPoints[slotName] = { x = slotPoint.x, y = slotPoint.y }
        end
    end

    return jsonPoints

end



function calculateMapPanelLocTest()

    local jsonPoints = {}

    -- Get display width and height
    local displayWidth = g_window.getDisplayWidth()
    local displayHeight = g_window.getDisplayHeight()

    -- Calculate GameWindow position
    local windowPosX = g_window.getX()
    local windowPosY = g_window.getY()
    local gameWindowPoint = { x = windowPosX, y = windowPosY }

    -- Get ParentRect properties
    local tempMapPanelChildren = modules.game_interface.gameMapPanel:getRect()

    -- Calculate ParentRect position relative to GameWindow
    local parentRectPoint = { 
        x = gameWindowPoint.x + tempMapPanelChildren.x, 
        y = gameWindowPoint.y + tempMapPanelChildren.y 
    }

    -- Calculate and print the 4 corners of ParentRect
    local topLeft = { x = parentRectPoint.x, y = parentRectPoint.y }
    local topRight = { x = parentRectPoint.x + tempMapPanelChildren.width, y = parentRectPoint.y }
    local bottomRight = { x = parentRectPoint.x + tempMapPanelChildren.width, y = parentRectPoint.y + tempMapPanelChildren.height }
    local bottomLeft = { x = parentRectPoint.x, y = parentRectPoint.y + tempMapPanelChildren.height }

    -- Calculate aspect ratio of InnerScreen
    local innerScreenAspectRatio = 630 / 460

    -- Calculate maximum InnerScreen dimensions that fit within ParentRect
    local innerScreenWidth = math.min(tempMapPanelChildren.width, tempMapPanelChildren.height * innerScreenAspectRatio)
    local innerScreenHeight = innerScreenWidth / innerScreenAspectRatio

    -- Calculate margins and position of InnerScreen within ParentRect
    local marginY = (tempMapPanelChildren.height - innerScreenHeight) / 2
    local innerScreenTopLeft = {
        x = parentRectPoint.x + (tempMapPanelChildren.width - innerScreenWidth) / 2,
        y = parentRectPoint.y + marginY
    }
    local innerScreenTopRight = {
        x = innerScreenTopLeft.x + innerScreenWidth,
        y = innerScreenTopLeft.y
    }
    local innerScreenBottomRight = {
        x = innerScreenTopRight.x,
        y = innerScreenTopLeft.y + innerScreenHeight
    }
    local innerScreenBottomLeft = {
        x = innerScreenTopLeft.x,
        y = innerScreenBottomRight.y
    }

    -- Calculate the size of the smaller rectangles within the inner rectangle
    local smallerRectWidth = innerScreenWidth / 15
    local smallerRectHeight = innerScreenHeight / 11

    -- Loop to calculate and add the middle points to the jsonPoints table
    for i = 1, 15 do

        for j = 1, 11 do
            local middlePoint = {
                x = math.floor(gameWindowPoint.x + innerScreenTopLeft.x + (i - 0.5) * smallerRectWidth),
                y = math.floor(gameWindowPoint.y + innerScreenTopLeft.y + (j - 0.5) * smallerRectHeight)
            }

            local key = i .. "x" .. j
            jsonPoints[key] = { x = middlePoint.x, y = middlePoint.y }
        end
    end

    printConsole(tableToString(jsonPoints))

end


function calculateMapPanelLoc()

    local jsonPoints = {}

    -- Get display width and height
    local displayWidth = g_window.getDisplayWidth()
    local displayHeight = g_window.getDisplayHeight()

    -- Calculate GameWindow position
    local windowPosX = g_window.getX()
    local windowPosY = g_window.getY()
    local gameWindowPoint = { x = windowPosX, y = windowPosY }

    -- Get ParentRect properties
    local tempMapPanelChildren = modules.game_interface.gameMapPanel:getRect()

    -- Calculate ParentRect position relative to GameWindow
    local parentRectPoint = { 
        x = gameWindowPoint.x + tempMapPanelChildren.x, 
        y = gameWindowPoint.y + tempMapPanelChildren.y 
    }

    -- Calculate and print the 4 corners of ParentRect
    local topLeft = { x = parentRectPoint.x, y = parentRectPoint.y }
    local topRight = { x = parentRectPoint.x + tempMapPanelChildren.width, y = parentRectPoint.y }
    local bottomRight = { x = parentRectPoint.x + tempMapPanelChildren.width, y = parentRectPoint.y + tempMapPanelChildren.height }
    local bottomLeft = { x = parentRectPoint.x, y = parentRectPoint.y + tempMapPanelChildren.height }

    -- Calculate aspect ratio of InnerScreen
    local innerScreenAspectRatio = 630 / 460

    -- Calculate maximum InnerScreen dimensions that fit within ParentRect
    local innerScreenWidth = math.min(tempMapPanelChildren.width, tempMapPanelChildren.height * innerScreenAspectRatio)
    local innerScreenHeight = innerScreenWidth / innerScreenAspectRatio

    -- Calculate margins and position of InnerScreen within ParentRect
    local marginY = (tempMapPanelChildren.height - innerScreenHeight) / 2
    local innerScreenTopLeft = {
        x = parentRectPoint.x + (tempMapPanelChildren.width - innerScreenWidth) / 2,
        y = parentRectPoint.y + marginY
    }
    local innerScreenTopRight = {
        x = innerScreenTopLeft.x + innerScreenWidth,
        y = innerScreenTopLeft.y
    }
    local innerScreenBottomRight = {
        x = innerScreenTopRight.x,
        y = innerScreenTopLeft.y + innerScreenHeight
    }
    local innerScreenBottomLeft = {
        x = innerScreenTopLeft.x,
        y = innerScreenBottomRight.y
    }

    -- Calculate the size of the smaller rectangles within the inner rectangle
    local smallerRectWidth = innerScreenWidth / 15
    local smallerRectHeight = innerScreenHeight / 11

    -- Loop to calculate and add the middle points to the jsonPoints table
    for i = 1, 15 do
        for j = 1, 11 do
            local middlePoint = {
                x = math.floor(gameWindowPoint.x + innerScreenTopLeft.x + (i - 0.5) * smallerRectWidth),
                y = math.floor(gameWindowPoint.y + innerScreenTopLeft.y + (j - 0.5) * smallerRectHeight - 30)
            }
            local key = i .. "x" .. j
            jsonPoints[key] = { x = middlePoint.x, y = middlePoint.y }
        end
    end

    return jsonPoints

end


function tableToString(tbl)
    local result = "{"
    for k, v in pairs(tbl) do
        result = result .. '"' .. k .. '": { "x": ' .. v.x .. ', "y": ' .. v.y .. ' }, '
    end
    result = result:sub(1, -3)  -- Remove the last comma and space
    return result .. "}"
end






function testUIAsave_butDontunderstand()
    printConsole('testUI function initiated')
    local tempMapPanelChildren = modules.game_interface.gameMapPanel:getChildren()

    -- Iterate through the children list and print their content
    for i, child in ipairs(tempMapPanelChildren) do
        printConsole('Child ' .. i .. ': ' .. tostring(child))
    end
    printConsole('testUI function closed')
end



function testUIAnotworking()
    printConsole('testUI function initiated')
    local tempTile = modules.game_interface.gameMapPanel:getTile(modules.game_interface.gameMapPanel.mousePos)
    local tempTileId = tempTile.asTile().getId()

    local tempItem = modules.game_inventory.inventoryWindow:getChildById(tostring(tempTileId))
    -- Assuming there's a function to get the position
    local position = tempItem:getPosition()

    -- Extracting the x and y coordinates
    local x = position.x
    local y = position.y

    -- Output or use the x, y coordinates as needed
    printConsole("Map position x = " .. x .. " y " .. y)
    printConsole('testUI function closed')
end

function testUInotWorking()
    printConsole('testUI function initiated')
    local tempMapPanelChildren= modules.game_interface.gameMapPanel:getChildren()
    printConsole("Tile position x = " .. tempTile.getPosition().x .. " y " .. tempTile.getPosition().y)
    local position = tempMapPanel

    -- Extracting the x and y coordinates
    local x = position.x
    local y = position.y

    -- Output or use the x, y coordinates as needed
    printConsole("Map position x = " .. x .. " y " .. y)
    printConsole('testUI function closed')
end


-- works great, displaying x,y of slot6
function testUISlot6()
    printConsole('testUI function initiated')
    local tempSlot = modules.game_inventory.inventoryWindow:recursiveGetChildById('slot6')

    -- Assuming there's a function to get the position
    local position = tempSlot:getPosition()

    -- Extracting the x and y coordinates
    local x = position.x
    local y = position.y

    -- Output or use the x, y coordinates as needed
    printConsole("Slot6 position x = " .. x .. " y " .. y)
    printConsole('testUI function closed')
end

function testUI3()
    printConsole('testUI function initiated')
    g_ui.importStyle('battlebutton')
    battleButton = modules.client_topmenu.addRightGameToggleButton('battleButton', tr('Battle') .. ' (Ctrl+B)', '/images/topbuttons/battle', toggle, false, 2)
    battleButton:setOn(true)
    battleWindow = g_ui.loadUI('battle', modules.game_interface.getRightPanel())
    g_keyboard.bindKeyDown('Ctrl+B', toggle)
    if battleButton:isOn() then
        battleWindow:close()
        battleButton:setOn(false)
    else
        battleWindow:open()
        battleButton:setOn(true)
    end
    printConsole('testUI function closed')
end


function testUI1()
    printConsole('testUI function initiated')
    optionsWindow = g_ui.displayUI('store')
    printConsole('testUI function closed')



end

function getCurrentWidgetPosition()
    printConsole('getCurrentWidgetPosition function initiated')
    local myWidget = UIWidget.create()
    -- Assuming g_window has a method getCurrentlyFocusedWidget
    local currentWidget = myWidget.getCurrentlyFocusedWidget()

    if currentWidget then
        local x, y = currentWidget:getX(), currentWidget:getY()
        print("Current widget position: X=" .. x .. ", Y=" .. y)
        return x, y
    else
        print("No widget is currently focused.")
        return nil
    end
end

function testUIWidgetHeight()
    -- Create an instance of UIWidget using the registered static function
    local myWidget = UIWidget.create()

    -- Check if the widget is valid
    if myWidget then
        -- Call the getHeight method
        local height = myWidget:getHeight()
        print("Height of the UIWidget is: " .. height)
    else
        print("Failed to create UIWidget instance")
    end
end


function screenTest1()
    printConsole('Screen test function initiated')
    local displaySize = g_window.getDisplaySize()  -- Get display size
    local message = "Display Size: width = " .. displaySize.width .. ", height = " .. displaySize.height
    printConsole(message)  -- Print display size

    g_settings = makesingleton(g_configs.getSettings())
    local size = { width = 1024, height = 600 }
    size = g_settings.getSize('window-size', size)
    g_window.resize(size)
end

--good script
function screenInfo()
    printConsole('Mouse test function initiated')

    -- Get and print window position
    local windowPosX = g_window.getX()
    local windowPosY = g_window.getY()
    local message = "!Window posX = " .. windowPosX .. ", posY = " .. windowPosY
    printConsole(message)

    -- Get and print window width and height separately
    local width = g_window.getWidth()
    local height = g_window.getHeight()
    printConsole("Window width = " .. width)
    printConsole("Window height = " .. height)

    -- Get and print display width and height
    local displayWidth = g_window.getDisplayWidth()
    local displayHeight = g_window.getDisplayHeight()
    printConsole("Display Width = " .. displayWidth)
    printConsole("Display Height = " .. displayHeight)

    -- Get mouse position
    local pos = g_window.getMousePosition()
    printConsole("Mouse posX = " .. pos.x .. ", posY = " .. pos.y)

    local tempMapPanelChildren = modules.game_interface.gameMapPanel:getRect()
    printConsole('ParentRect x ' .. tostring(tempMapPanelChildren.x) .. ' y ' .. tostring(tempMapPanelChildren.y))
    printConsole('ParentRect width ' .. tostring(tempMapPanelChildren.width) .. ' height ' .. tostring(tempMapPanelChildren.height))
    
    printConsole('ParentRect top ' .. tostring(modules.game_interface.gameMapPanel:getMarginTop()) .. ' bottom ' .. tostring(modules.game_interface.gameMapPanel:getMarginBottom()))
end

function spyLevels()        
    local player = g_game.getLocalPlayer()
    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local dimension = modules.game_interface.getMapPanel():getVisibleDimension()
    local spectators = g_map.getSpectatorsInRangeEx(player:getPosition(), true, math.floor(dimension.width / 2 + 2), math.floor(dimension.width / 2 + 2), math.floor(dimension.height / 2 + 2), math.floor(dimension.height / 2 + 2))

    local creaturesInfo = {}
    for _, creature in ipairs(spectators) do
        -- Collecting creature details
        local creatureData = {
            Name = creature:getName(),
            Id = creature:getId(),
            HealthPercent = creature:getHealthPercent(),
            PositionX = creature:getPosition().x,
            PositionY = creature:getPosition().y,
            PositionZ = creature:getPosition().z,
            IsNpc = creature:isNpc(),
            IsPlayer = creature:isPlayer(),
            IsMonster = creature:isMonster()
        }
        
        table.insert(creaturesInfo, creatureData)
    end
    
    return creaturesInfo
end


function getEqData()
    local inventoryData = {}
    local slotNames = {
        "helmet", "amulet", "backpack", "armor",
        "hand_right", "hand_left", "legs", "boots",
        "ring", "arrows"
    }

    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    -- Iterate through inventory slots 1 to 10
    for slot = 1, 10 do
        local item = player:getInventoryItem(slot)
        if item then
            local itemName = item:getName() or "Unknown Item"
            inventoryData[tostring(slot)] = {
                slotName = slotNames[slot],
                itemId = item:getId(),
                itemCount = item:getCount(),
                itemSubType = item:getSubType(),
                itemName = itemName
            }
        else
            inventoryData[tostring(slot)] = {
                slotName = slotNames[slot],
                itemId = nil,
                itemCount = nil,
                itemSubType = nil,
                itemName = "Empty Slot"
            }
        end
    end

    return inventoryData
end


function whatIsInEq()
    local player = g_game.getLocalPlayer()
    -- Iterate through inventory slots 1 to 10
    for slot = 1, 10 do
        local item = player:getInventoryItem(slot)
        if item then
            printConsole("!Item in slot " .. slot .. ": ID = " .. item:getId() .. ", Count: " .. item:getCount() .. ", SubType = " .. item:getSubType() .. ", Item Name: " .. item:getName())
        else
            printConsole("No item found in slot " .. slot)
        end
    end
end


function whatIsinMyRightHand()
    if not g_game.isOnline() then
        printConsole('Is not in game')
        return
    end

    local player = g_game.getLocalPlayer()

    if not player then
        printConsole('Couldn\'t get player, are you in game?')
        return
    end

    local item = player:getInventoryItem(6)
    if item then
        printConsole("!Item in slot 6: ID = " .. item:getId() .. ", Count: " .. item:getCount() .. ", SubType = " .. item:getSubType())
    else
        printConsole("No item found in slot 6")
    end

end

function useFishingRodTest()
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

    local playerPos = player:getPosition()
    local allTiles = g_map.getTiles(playerPos.z)
    local filteredTiles = filterTilesInRange(allTiles, playerPos)

    if #filteredTiles > 0 then
        local attempts = 0
        local maxAttempts = 10 -- Maximum number of attempts to find a suitable tile

        while attempts < maxAttempts do
            local randomIndex = math.random(#filteredTiles)
            local tile = filteredTiles[randomIndex]
            printConsole("Tile pos: " .. tostring(tile:getPosition().x) .. ", " .. tostring(tile:getPosition().y) .. ", " .. tostring(tile:getPosition().z)) 

            if tile then
                local topThing = tile:getTopUseThing()
                if topThing and table.contains({618, 619, 620}, topThing:getId()) then
                    
                    printConsole("Using item with suitable tile: " .. tostring(topThing:getId()) .. " Top thing: " .. tostring(topThing))
                    g_game.useWith(foundItem, topThing, 1)
                    return -- Exit the function after successful use
                else
                    printConsole("Tile does not meet the condition, checking another tile")
                end
            else
                printConsole("Failed to get a random tile")
            end

            attempts = attempts + 1
        end

        printConsole("Failed to find a suitable tile after " .. maxAttempts .. " attempts")
    else
        printConsole("No suitable tiles found at the current level")
    end
end

-- Helper function to filter tiles within a specified range
function filterTilesInRange(tiles, playerPos)
    local filtered = {}
    for i, tile in ipairs(tiles) do
        if isTileInRange(tile:getPosition(), playerPos) then
            table.insert(filtered, tile)
        end
    end
    return filtered
end

function isTileInRange(tilePos, playerPos)
    local dx = tilePos.x - playerPos.x
    local dy = tilePos.y - playerPos.y
    return dx >= 2 and dx <= 7 and dy >= -5 and dy <= 5
end

function useFishingRod(arg)
    -- Accessing 'x', 'y', and 'z' directly from the 'data' table
    local x = tonumber(arg.data.tilePosition.x)
    local y = tonumber(arg.data.tilePosition.y)
    local z = tonumber(arg.data.tilePosition.z)

    printConsole("Fishing function: x=" .. tostring(x) .. ", y=" .. tostring(y) .. ", z=" .. tostring(z))

    local player = g_game.getLocalPlayer()
    local playerPos = player:getPosition()
    local allTiles = g_map.getTiles(playerPos.z)

    local foundTile = nil
    for _, tile in ipairs(allTiles) do
        local tilePos = tile:getPosition()
        if tilePos.x == x and tilePos.y == y and tilePos.z == z then
	    printConsole("Tile Found.")	
            foundTile = tile
            break
        end
    end

    if not foundTile then
        printConsole("Error: No matching tile found.")
        return
    end
    printConsole("Getting top thing.")	
    local itemId = 3483 -- ID of the fishing rod
    local foundItem = g_game.findPlayerItem(itemId, -1)
    local topThing = foundTile:getTopUseThing()
    if topThing then
        g_game.useWith(foundItem, topThing, 1)
    else
        printConsole("Error: No top thing on the found tile.")
    end
end




function targetAttack(arg)
    printConsole("Attack target function initiated.")
    printConsole("------")
    printConsole(arg.data) -- arg.data is the actual JSON data passed from C++
    printConsole("------")

    -- Assuming arg.data is a table with the ratId, extract it
    local ratId = arg.data.ratId
    printConsole("ratId type:", type(ratId))
    printConsole("ratId value:", ratId)

    local creature = g_map.getCreatureById(ratId)

    if creature then
        g_game.attack(creature)
    else
        printConsole("Creature not found with ID:", ratId)
    end
end


function temp(data)
    printConsole("targetId type:", type(data.ratId))
    printConsole("targetId value:", data.ratId)
    local creature = g_map.getCreatureById(data.ratId)

    if creature then
        g_game.attack(creature)
    else
        printConsole("Creature not found with ID:", data.ratId)
    end

end

function init(event)
    -- Bind game start and end events
    connect(g_game, {
        onGameStart = gameStarted(event),
        onGameEnd = gameEnded
    })
end

-- Function called when entering the game world
function gameStarted(event)
    local message = '{ "status": "Game started." }'
    printConsole(message)
    -- Create a repeated event
    timerEvent = cycleEvent(function() tick(event) end, 1000) -- 500 milliseconds or 0.5 seconds
end


-- Function called periodically according to settings made in gameStarted()
function tick(event)
    local message = '{ "status": "Tick activated." }'
    printConsole(message)
    getGameData(event) -- Call displayList here
end



-- Function called when exiting the game world
function gameEnded()
    if timerEvent ~= nil then
        removeEvent(timerEvent)
    end
    local message = '{ "status": "Quit from game world" }'
    printConsole(message)
end

function tableToJsonString(tbl)
    local result = {}
    local isArray = true
    local maxIndex = 0

    for key, _ in pairs(tbl) do
        if type(key) ~= "number" then
            isArray = false
            break
        else
            maxIndex = math.max(maxIndex, key)
        end
    end

    for key, v in pairs(tbl) do
        local elementString
        if type(v) == "table" then
            elementString = tableToJsonString(v)
            if not isArray then
                elementString = '"' .. key .. '":' .. elementString
            end
        else
            local valueString = type(v) == "string" and '"' .. v .. '"' or tostring(v)
            elementString = isArray and valueString or ('"' .. key .. '":' .. valueString)
        end
        table.insert(result, elementString)
    end

    if isArray and #result ~= maxIndex then
        isArray = false -- Fallback to object if array has non-sequential keys
    end

    local openBracket, closeBracket = isArray and '[' or '{', isArray and ']' or '}'
    return openBracket .. table.concat(result, ",") .. closeBracket
end

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


local function formatKey(x, y, z)
    return string.format("%05d%05d%02d", x, y, z)
end



function getGameData(event)

    _responseSent = false  -- Reset global flag at the beginning of the function

    if not g_game.isOnline() then
        printConsole('{ "status": "Is not in game" }')
        return
    end

    local player = g_game.getLocalPlayer()

    local dimension = modules.game_interface.getMapPanel():getVisibleDimension()
    local spectators = g_map.getSpectatorsInRangeEx(player:getPosition(), false,
                                                     math.floor(dimension.width / 2),
                                                     math.floor(dimension.width / 2) + 1,
                                                     math.floor(dimension.height / 2),
                                                     math.floor(dimension.height / 2) + 1)

    -- Initialize the main JSON-like structure
    local gameData = {
        characterInfo = {},
        EqInfo = {},
        containersInfo = {},
        battleInfo = {},
	areaInfo = {},
        screenInfo = {},
        spyLevelInfo = {},
    }

    gameData.EqInfo = getEqData()
    gameData.containersInfo = getOpenContainersData()
    gameData.spyLevelInfo = spyLevels()
 
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

    -- Collect data about area around player
    gameData.areaInfo = {
        tiles = {}
    }
    local targetPosition = player:getPosition()
    local tiles = g_map.getTiles(targetPosition.z)

    for _, tile in ipairs(tiles) do
        local x = tile:getPosition().x
        local y = tile:getPosition().y
        local z = tile:getPosition().z
        local key = formatKey(x, y, z)

--        local tileFlags = tile:getFlags()
        local isWalkable = tile:isWalkable()
        local isPathable = tile:isPathable()
        local isClickable = tile:isClickable()
        -- local groundSpeed = tile:getGroundSpeed()

        gameData.areaInfo.tiles[key] = {
            isWalkable = isWalkable,
            isPathable = isPathable,
            isClickable = isClickable,
--            groundSpeed = groundSpeed,
--            tileFlags = tileFlags,
            items = {}
        }

        local topThingList = tile:getItems()
        if topThingList and #topThingList > 0 then
            for j, topThing in ipairs(topThingList) do
                local itemInfo = {
                    id = topThing:getId()
                    -- Remember to add a comma after id = topThing:getId()
                }
                gameData.areaInfo.tiles[key].items[tostring(j)] = itemInfo.id
            end
        end
    end

    -- Collect data of creatures in range for battle information
    for _, creature in pairs(spectators) do
        if checkValidCreatureForListing(creature) then
            table.insert(gameData.battleInfo, {
                Name = creature:getName(),
                Id = creature:getId(),
                HealthPercent = creature:getHealthPercent(),
                PositionX = creature:getPosition().x,
                PositionY = creature:getPosition().y,
                PositionZ = creature:getPosition().z,
                IsNpc = creature:isNpc(),
                IsPlayer = creature:isPlayer(),
                IsMonster = creature:isMonster(),
            })
        end
    end


    gameData.screenInfo = {
        inventoryPanelLoc = calculateInventoryPositions(),
        mapPanelLoc = calculateMapPanelLoc()
    }

    local sent = event:send(gameData)

    if sent then
        _responseSent = true  -- Set global flag if the message was sent successfully
    else
        printConsole("Failed to send game data")
    end
end



function initialGetGameData(event)
    printConsole('{ "status": "Innitial function started." }')

    if not g_game.isOnline() then
        printConsole('{ "status": "Is not in game" }')
        return
    end

    local player = g_game.getLocalPlayer()

    local dimension = modules.game_interface.getMapPanel():getVisibleDimension()
    local spectators = g_map.getSpectatorsInRangeEx(player:getPosition(), false,
                                                     math.floor(dimension.width / 2),
                                                     math.floor(dimension.width / 2) + 1,
                                                     math.floor(dimension.height / 2),
                                                     math.floor(dimension.height / 2) + 1)
    
    printConsole('1')
    -- Initialize the main JSON-like structure
    local gameData = {
        characterInfo = {},
        screenInfo = {}
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
    printConsole('2')
    gameData.screenInfo = {
        inventoryPanelLoc = calculateInventoryPositions(),
        mapPanelLoc = calculateMapPanelLoc()
    }
    printConsole('5')
    local sent = event:send(gameData)
    printConsole('6')
    if sent then
        _responseSent = true  -- Set global flag if the message was sent successfully
    else
        printConsole("Failed to send game data")
    end
end


function test(event)
    _responseSent = false  -- Reset global flag at the beginning of the function

    printConsole(_responseSent)

    if not event or not event.from or not event.data then
        printConsole("Invalid event data")
        return
    end

    local sent = event:send({
        msg = 'hi from lua'
    })

    if sent then
        _responseSent = true  -- Set global flag
    else
        printConsole("Cannot send the message")
    end
    
    printConsole(_responseSent)
end




function sayFunction(textString, numberString)
    if g_game.isOnline() then
       local message = textString .. numberString
       g_game.talk(message)
    end
end




function tempHello()
        
    -- say hello in chat
    if g_game.isOnline() then
        g_game.talk('hello')
    end
        
end


function getPos()
    if not g_game.isOnline() then
        sendToScala('{ "status": "Is not in game" }')
        return
    end

    local player = g_game.getLocalPlayer()
    if not player then
        sendToScalaServer('{ "error": "Couldn\'t get player, are you in game?" }')
        return
    end

    local playerXPos = player:getPosition().x
    local playerYPos = player:getPosition().y
    local playerZPos = player:getPosition().z

    -- Manually constructing the JSON string
    local posString = string.format('{ "!Player pos": { "x": %d, "y": %d, "z": %d } }', playerXPos, playerYPos, playerZPos)
    printConsole(posString)
    sendToScalaServer(posString)

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

buttonFunctions()