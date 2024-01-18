function init()
    if g_app ~= nil and g_window ~= nil then
        -- we can change the game window title
        g_window.setTitle(g_app.getName() .. " - Lua Mod Loaded!")
        
        -- lets add a button in right top position (id, text, icon, callback)
        local btn = modules.client_topmenu.addRightButton('luaModInfoBtn', tr('Lua Mod'), '/images/topbuttons/debug', luaModInfo)
        
        -- btn is a variable now but I don't know what it is useful for
        -- so I left it as-is
    end
end

-- a callback for our custom button
-- see mod_test.lua for more information
function luaModInfo()
    printConsole('About button clicked')
    displayInfoBox('About Lua Mod', 'Lua Mod v1.0 (test drive)')
end

-- see mod_test.lua for more information
init()
