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
    printConsole('game started')
    
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

-- this function will be called according to the settings made in gameStarted()
function tick()
    printConsole('test')
end

-- call our main function
init()