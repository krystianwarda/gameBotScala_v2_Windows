function buttonFunctions()
    local message = '{ "status": "Button functions activated." }'
    printConsole(message)
    -- register some keyboard shortcuts
    g_keyboard.bindKeyDown('Ctrl+D', useFishingRodTest)
    g_keyboard.bindKeyDown('Ctrl+E', whatIsinMyRightHand)
    g_keyboard.bindKeyDown('Ctrl+C', calculateInventoryPanelLocTest)
    g_keyboard.bindKeyDown('Ctrl+A', calculateMapPanelLocTest)
end




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


function calculateInventoryPanelLoc()


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

-- function location a tile, but in game terms not in px
function tileUnderCursor()
    local tempTile = modules.game_interface.gameMapPanel:getTile(modules.game_interface.gameMapPanel.mousePos)

    -- Assuming there's a function to get the position
    local position = tempTile:getPosition()

    -- Extracting the x and y coordinates
    local x = position.x
    local y = position.y

    -- Output or use the x, y coordinates as needed
    printConsole("Tile position x = " .. x .. " y " .. y)
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
    timerEvent = cycleEvent(function() tick(event) end, 5000) -- 500 milliseconds or 0.5 seconds
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


-- Function to get list of spectators
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
        battleInfo = {},
	areaInfo = {},
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

        gameData.areaInfo.tiles[key] = {}

        local topThingList = tile:getItems()
        if topThingList and #topThingList > 0 then
            for j, topThing in ipairs(topThingList) do
                local itemInfo = {
                    id = topThing:getId()
                    -- Include additional properties of topThing if needed
                }
                gameData.areaInfo.tiles[key][tostring(j)] = itemInfo.id
            end
        end
    end

    -- Optionally, convert gameData.areaInfo to a JSON string if needed
    -- local jsonString = toJSON(gameData.areaInfo)


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


    gameData.screenInfo = {
        tuest = "tibiatuest",
        inventoryPanelLoc = calculateInventoryPanelLoc(),
        mapPanelLoc = calculateMapPanelLoc()
        
    }

    local sent = event:send(gameData)

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