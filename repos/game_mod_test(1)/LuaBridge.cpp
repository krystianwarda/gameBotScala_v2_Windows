#include "LuaBridge.h"
#include "include.h"
#include <winsock2.h>
#include <Windows.h>

#define AddrToParams(addr) addr.sin_addr.S_un.S_un_b.s_b1, addr.sin_addr.S_un.S_un_b.s_b2, addr.sin_addr.S_un.S_un_b.s_b3, addr.sin_addr.S_un.S_un_b.s_b4


DWORD LuaBridge::g_moduleBase = 0;
void* LuaBridge::g_luaInterface = nullptr;
lua_State* g_luaState = nullptr;  // Define g_luaState


int LuaBridge::__errHandler(lua_State* L)
{
    size_t l;
    const char* m = lua_tolstring(L, -1, &l);
    if (m && l > 0)
        std::cout << STDOUT_COLOR(SC_RED) "[Lua Error] " STDOUT_COLOR_RESET << m << std::endl;
    return 1;
}

LuaBridge::LuaBridge() {
    try {
        this->m_luaState = reinterpret_cast<lua_State*>(*(DWORD*)this->g_luaInterface);
        std::cout << "Lua state set from g_luaInterface." << std::endl;

        if (!this->m_luaState) {
            this->m_luaState = luaL_newstate();
            if (!this->m_luaState) {
                throw std::runtime_error("Failed to initialize Lua state");
            }
            luaL_openlibs(this->m_luaState);
            std::cout << "New Lua state initialized." << std::endl;
        }
        // Register printConsole as before
        this->registerFunction("printConsole", LuaBridge::__printConsole);

        // Initialize TcpServer for Scala client communication
        this->tcpServer = new TcpServer("127.0.0.1", 9997);
        this->tcpServer->Start();

        CloseHandle(CreateThread(nullptr, 0, (LPTHREAD_START_ROUTINE)&LuaBridge::T_Receive, this, 0, nullptr));

        // Register the static wrapper function
        lua_pushlightuserdata(this->m_luaState, this);
        //lua_pushcclosure(this->m_luaState, LuaBridge::sendToScalaServerWrapper, 1);
        lua_setglobal(this->m_luaState, "sendToScalaServer");
    }
    catch (const std::exception& e) {
        std::cerr << "Error in LuaBridge constructor: " << e.what() << std::endl;
    }

}

void LuaBridge::gen_lua_table(lua_State* L, json _json, const std::string& name, bool arr)
{
    lua_createtable(L, 0, _json.size());
    
    for (const auto& [key, value] : _json.items())
    {
        if (arr)
            lua_pushnumber(L, std::stoi(key) + 1);
        
        if (value.is_structured())
        {
            gen_lua_table(L, value, key, value.is_array());
            if (arr) lua_insert(L, -2);
        }
        else if (value.is_boolean())
            lua_pushboolean(L, (bool)value);
        else if (value.is_number_float())
            lua_pushnumber(L, (lua_Number)value);
        else if (value.is_number_integer() || value.is_number_unsigned())
            lua_pushinteger(L, (lua_Integer)value);
        else if (value.is_string())
            lua_pushstring(L, ((const std::string&)value).c_str());
        else if (value.is_null())
            lua_pushnil(L);


        if (arr) lua_settable(L, -3);
        else lua_setfield(L, -2, key.c_str());
    }
}

void LuaBridge::read_lua_table(lua_State* L, json& _json, int idx)
{    
    lua_pushnil(L);
    while (lua_next(L, idx - 1) != 0)
    {
        auto keytype = lua_type(L, -2);
        auto valtype = lua_type(L, -1);

        std::string key;
        if (keytype == LUA_TSTRING)
            key = lua_tostring(L, -2);
        else if (keytype == LUA_TNUMBER)
            key = std::to_string(lua_tointeger(L, -2));

        switch (valtype)
        {
        case LUA_TBOOLEAN: _json.push_back(json::object_t::value_type({key, (bool)lua_toboolean(L, -1)})); break;
        case LUA_TNUMBER:
        {
            lua_Number dval = lua_tonumber(L, -1);
            lua_Integer ival = lua_tointeger(L, -1);
            
            if ((lua_Number)ival == dval)
                _json.push_back(json::object_t::value_type({key, ival}));
            else
                _json.push_back(json::object_t::value_type({key, dval}));
            
            break;
        }
        case LUA_TSTRING: _json.push_back(json::object_t::value_type({key, std::string(lua_tostring(L, -1))})); break;
        case LUA_TNIL:
        case LUA_TNONE: _json.push_back(json::object_t::value_type({key, nullptr})); break;
        
        case LUA_TTABLE:
        {
            json _tbl;
            read_lua_table(L, _tbl);
            _json.push_back(json::object_t::value_type({key, _tbl}));
            break;
        }
        default:
            break;
        }
        
        lua_pop(L, 1);
    }
}

DWORD __stdcall LuaBridge::T_Receive(LuaBridge* _this)
{
    auto& srv = _this->tcpServer;
    auto& L = _this->m_luaState;

    /*
    while (tcp->connectionStatus)
    {
        auto s = tcp->receive();
        if (s.empty())
            continue;

        json j = json::parse(s), r;
        std::string fn = j["__command"];
        lua_getglobal(L, fn.c_str());

        if (!lua_isfunction(L, -1))
        {
            tcp->send("{\"__status\":\"error\", \"message\": \"function does not exist\"}");
            continue;
        }

        j.erase("__command");
        if (!j.empty())
            gen_lua_table(L, j);
        else
            lua_pushnil(L);

        if(!lua_pcall(L, 1, 1, 0))
        {
            if (lua_istable(L, -1))
            {
                json j;
                read_lua_table(L, j);
                j.push_back({"__status", "ok"});
                tcp->send(j.dump());
            }
            else if (lua_isnil(L, -1))
                tcp->send("{\"__status\":\"ok\"}");

            lua_pop(L, 1);
        }
        else
        {
            std::string err(lua_tostring(L, -1));
            lua_pop(L, 1);

            if (!err.empty())
                std::cout << STDOUT_COLOR(SC_RED) "[Lua Remote Call Error] " STDOUT_COLOR_RESET << err << std::endl;

            json j = {
                {"__status", "error"},
                {"message", err}
            };
            tcp->send(j.dump());
        }
    }
    */

    while (srv->IsRunning())
    {
        std::string data;
        SOCKET sck;
        sockaddr_in addr{};
        if (srv->PollRequest(sck, addr, data))
        {
            try
            {
                json j = json::parse(data);
                if (!j.count("__command"))
                {
                    srv->SendOn(sck, "{}");
                    srv->SendOn(sck, "{\"__status\": \"error\", \"message\": \"no function name given\"}");
                    srv->ShutdownClient(sck);
                    continue;
                }

                std::string __cmd = j["__command"];
                j.erase("__command");

                lua_getglobal(L, __cmd.c_str());

                if (!lua_isfunction(L, -1))
                {
                    srv->SendOn(sck, "{\"__status\":\"error\", \"message\": \"function does not exist\"}");
                    srv->ShutdownClient(sck);
                    continue;
                }



                /*
                    Parameter structure for target function:
                    arg = {
                        data = <json from client>
                        from = <client address (informational, no real use)>
                        [1] = <socket handle>
                        [2] = <server class pointer>
                        send() = <a function to send data to client>
                    }
                */

                // arg
                lua_createtable(L, 0, 2); // [] -> [[arg], [arg.data]]

                // arg.data
                gen_lua_table(L, j); // -> [...]
                lua_setfield(L, -2, "data"); // data = [...]

                // arg:send()
                lua_pushcfunction(L, sendfn); // -> [send()]
                lua_setfield(L, -2, "send"); // send = send()

                // socket handle
                lua_pushinteger(L, 1); // [1]
                lua_pushinteger(L, sck); // [1, sck]
                lua_settable(L, -3); // arg[1] = sck

                // server pointer
                // since a pointer is a numeric address, we can store the address directly without creating a user data
                lua_pushinteger(L, 2); // [2]
                lua_pushinteger(L, (lua_Integer)srv); // [2, srv]
                lua_settable(L, -3); // arg[2] = srv

                // arg.from
                lua_pushfstring(L, "%d.%d.%d.%d:%d", AddrToParams(addr), htons(addr.sin_port)); // [address string]
                lua_setfield(L, -2, "from"); // arg.from = [address string]

                int e = lua_pcall(L, 1, 0, 0);

                // error handling
                if (e != 0)
                {
                    auto msg = lua_tostring(L, -1);
                    std::cout << STDOUT_COLOR(SC_RED) "[Lua Remote Call Error - \"" << __cmd << "()\"] " STDOUT_COLOR_RESET << msg << std::endl;
                    srv->SendOn(sck, json({
                        {"__status", "error"},
                        {"message", msg}
                        }).dump());
                }
                else srv->SendOn(sck, "{\"__status\": \"ok\"}");
            }
            catch (const json::exception& ex)
            {
                srv->SendOn(sck, json({
                    {"__status", "error"},
                    {"message", ex.what()}
                    }).dump());
            }

            srv->ShutdownClient(sck);
        }
    }
    return 0;
}

//DWORD __stdcall LuaBridge::T_Receive(LuaBridge* _this)
//{
//    auto& srv = _this->tcpServer;
//    auto& L = _this->m_luaState;
//
//    while (srv->IsRunning())
//    {
//        std::string data;
//        SOCKET sck;
//        sockaddr_in addr{};
//        if (srv->PollRequest(sck, addr, data))
//        {
//            try
//            {
//                json j = json::parse(data);
//                if (!j.count("__command"))
//                {
//                    srv->SendOn(sck, "{}");
//                    srv->SendOn(sck, "{\"__status\": \"error\", \"message\": \"no function name given\"}");
//                    srv->ShutdownClient(sck);
//                    continue;
//                }
//
//                std::string __cmd = j["__command"];
//                j.erase("__command");
//
//                lua_getglobal(L, __cmd.c_str());
//
//                if (!lua_isfunction(L, -1))
//                {
//                    srv->SendOn(sck, "{\"__status\":\"error\", \"message\": \"function does not exist\"}");
//                    srv->ShutdownClient(sck);
//                    continue;
//                }
//
//                // Pushing arguments onto the Lua stack
//                int argCount = 0;
//                if (j.contains("param1")) {
//                    lua_pushstring(L, j["param1"].get<std::string>().c_str());
//                    argCount++;
//                }
//                if (j.contains("param2")) {
//                    lua_pushstring(L, j["param2"].get<std::string>().c_str());
//                    argCount++;
//                }
//
//                // Call Lua function
//                int e = lua_pcall(L, argCount, 0, 0);
//
//                // Error handling
//                if (e != 0)
//                {
//                    auto msg = lua_tostring(L, -1);
//                    std::cout << STDOUT_COLOR(SC_RED) "[Lua Remote Call Error - \"" << __cmd << "()\"] " STDOUT_COLOR_RESET << msg << std::endl;
//                    srv->SendOn(sck, json({
//                        {"__status", "error"},
//                        {"message", msg}
//                        }).dump());
//                }
//                else srv->SendOn(sck, "{\"__status\": \"ok\"}");
//            }
//            catch (const json::exception& ex)
//            {
//                srv->SendOn(sck, json({
//                    {"__status", "error"},
//                    {"message", ex.what()}
//                    }).dump());
//            }
//
//            srv->ShutdownClient(sck);
//        }
//    }
//    return 0;
//}


int LuaBridge::sendfn(lua_State* L)
{
    luaL_checktype(L, 1, LUA_TTABLE); // obj:send [obj]
    luaL_checktype(L, 2, LUA_TTABLE); // obj:send [arg]

    json j;
    read_lua_table(L, j);   // [arg]

    j.push_back({"__status", "ok"});

    lua_pop(L, 1); // [arg] -> [obj]

    // _sckfd = obj[1]
    lua_pushnumber(L, 1); // 1 -> index
    lua_gettable(L, -2); // [obj[index]] == obj[1] "socket number"
    int _sckfd = (int)lua_tointeger(L, -1); // -> _sckfd

    lua_pop(L, 1); // [_sckfd] -> []

    // _sckfd = obj[1]
    lua_pushnumber(L, 2); // 2 -> index
    lua_gettable(L, -2); // [obj[index]] == obj[2] "server pointer"
    TcpServer* _srv = reinterpret_cast<TcpServer*>(lua_tointeger(L, -1)); // -> _srv

    lua_pop(L, 2); // [_srv, obj] -> []

    lua_pushboolean(L, _srv->SendOn(_sckfd, j.dump()));
    return 1;
}


LuaBridge& LuaBridge::getInstance() {
    if (!instance) {
        std::cout << "Initializing LuaBridge instance" << std::endl;
        instance.reset(new LuaBridge());
    }
    return *instance;
}

bool LuaBridge::isInitialized() const {
    return this->m_luaState != nullptr;
}

LuaBridge::~LuaBridge() {
    if (tcpServer) {
        delete tcpServer;
        tcpServer = nullptr;
    }

    if (m_luaState) {
        lua_close(m_luaState);
        m_luaState = nullptr;
    }

    // Clean up other resources if necessary
}

void LuaBridge::LoadScript(const std::string& script, const std::string& name)
{
    int rc = luaL_loadbuffer(this->m_luaState, script.c_str(), script.length(), name.c_str());
    if (!rc)
    {
        try
        {
            // try to execute the script immediately
            int pss = lua_gettop(this->m_luaState);
            int efi = pss;
            lua_pushcfunction(this->m_luaState, LuaBridge::__errHandler);
            lua_insert(this->m_luaState, efi);
            rc = lua_pcall(this->m_luaState, 0, LUA_MULTRET, efi);
            lua_remove(this->m_luaState, efi);

            if (rc)
            {
                size_t len = 0;
                const char* emsg = lua_tolstring(this->m_luaState, -1, &len);
                lua_pop(this->m_luaState, 1);
                if (emsg && len > 0)
                    std::cout << STDOUT_COLOR(SC_RED) "[Lua Error] " STDOUT_COLOR_RESET << emsg << std::endl;

                lua_pop(this->m_luaState, lua_gettop(this->m_luaState));
            }
        }
        catch (std::exception e)
        {
            // sometimes we end up here magically
            std::cout << "[Lua Call Error] " << e.what() << std::endl;
        }
        std::cout << STDOUT_COLOR(SC_GREEN) "[Lua]" STDOUT_COLOR(SC_GRAY) " Loaded Lua buffer: " STDOUT_COLOR_RESET << name << std::endl;
    }
    else
    {
        size_t len = 0;
        const char* emsg = lua_tolstring(this->m_luaState, -1, &len);
        lua_pop(this->m_luaState, 1);
        if (emsg && len > 0)
            std::cout << STDOUT_COLOR(SC_RED) "[Lua Error] " STDOUT_COLOR_RESET << emsg << std::endl;
    }
}

void LuaBridge::LoadScriptFile(const std::string& path)
{
    FILE* hLuaFile = nullptr;
    fopen_s(&hLuaFile, path.c_str(), "rb");

    std::string cpath = path.c_str();
    cpath.insert(cpath.cbegin(), '@');
    for (char& refC : cpath)
        if (refC == '\\')
            refC = '/';

    this->LoadScriptFile(hLuaFile, cpath);
    if (hLuaFile) fclose(hLuaFile);
}

void LuaBridge::LoadScriptFile(FILE* fhandle, const std::string& name)
{
    if (!fhandle) return;
    fseek(fhandle, 0, SEEK_END);
    int codeSize = ftell(fhandle);
    fseek(fhandle, 0, SEEK_SET);

    if (codeSize > 0)
    {
        char* code = new char[codeSize + 1];
        code[codeSize] = 0;

        fread_s(code, codeSize, 1, codeSize, fhandle);
        this->LoadScript(code, name);

        delete[] code;
    }
}

void LuaBridge::registerFunction(const std::string& name, lua_CFunction func)
{
    lua_pushcfunction(this->m_luaState, func);
    lua_setglobal(this->m_luaState, name.c_str());
}



int LuaBridge::__printConsole(lua_State* L)
{
    // pop the parameter of printConsole
    size_t l = 0;
    const char* str = lua_tolstring(L, -1, &l);
    lua_pop(L, 1);

    // find where we get called from
    lua_Debug dbg = { 0 };
    std::string funcPath;

    int level = 0, ex;
    while (1)
    {
        // iterate over the stack for functions
        if (lua_getstack(L, level, &dbg) == 1)
            ex = lua_getinfo(L, "f", &dbg);
        else
            lua_pushnil(L); // nothing in the stack, push a null value to check below

        const char* type = luaL_typename(L, -1);
        const bool iscf = lua_iscfunction(L, -1) == 1;
        // do we have a lua-only function? (we have to have one because we are called from there)
        if (lua_isfunction(L, -1) && !lua_iscfunction(L, -1))
        {
            lua_Debug dbg2 = { 0 };
            lua_getinfo(L, ">Sn", &dbg2); // retrieve function information
            if (dbg2.source && *dbg2.source == '@') // do we have a valid source?
            {
                funcPath = "[";
                funcPath.append(dbg2.source);
                funcPath.append("]");
            }
            break; // we have everything we need, exit the loop
        }
        else if (!lua_isnil(L, -1)) // a.k.a. isNil()
        {
            lua_pop(L, 1); // we have a null that we have passed above, so pop it since it is useless
            break; // we have nothing we need, exit the loop
        }
        lua_pop(L, 1); // go to next thing in the stack
        level++;
    }

    // do we have a valid string?
    if (str && l)
    {
        // do we know where did we come from?
        if (funcPath.empty())
            funcPath = "[unknown source]";

        std::cout << STDOUT_COLOR(SC_MAGENTA) << funcPath << STDOUT_COLOR_RESET ": " << str << std::endl;
    }

    return 1;
}



void LuaBridge::CallFunction(const std::string& funcName) {
    std::cout << "Calling Lua function: " << funcName << std::endl;
    if (!m_luaState) {
        std::cerr << "Lua state is null in CallFunction." << std::endl;
        throw std::runtime_error("Lua state is null");
    }
    std::cout << "m_luaState is checked: " << funcName << std::endl;
    lua_getglobal(this->m_luaState, funcName.c_str());
    if (lua_isfunction(this->m_luaState, -1)) {
        lua_pcall(this->m_luaState, 0, 0, 0);
    }
}
