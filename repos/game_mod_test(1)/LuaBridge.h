#pragma once
#include "TcpServer.hpp"  // Include TcpServer header
#include <winsock2.h>
#include <Windows.h>

#include <iostream>
#include <functional>
#include <string>
#include <mutex>
#include "include/lua.hpp"
#include "include/lauxlib.h"
#pragma comment(lib, "lua5.1.lib")

#include <nlohmann/json.hpp>
using json = nlohmann::json;

#ifndef LUABRIDGE_H
#define LUABRIDGE_H
class LuaBridge {
    TcpServer* tcpServer; // TCP Server for Scala client communication
    lua_State* m_luaState;

    static int __printConsole(lua_State* L);
    static int __errHandler(lua_State* L);
    //int sendToScalaServer(lua_State* L);
    //static int sendToScalaServerWrapper(lua_State* L);

    static DWORD __stdcall T_Receive(LuaBridge *_this);
    static void gen_lua_table(lua_State *L, json _json, const std::string &name = "", bool arr = false);
    static void read_lua_table(lua_State *L, json &_json, int idx = -1);

    static int sendfn(lua_State* L);

public:
    LuaBridge();
    ~LuaBridge();
    bool isInitialized() const;

    static std::mutex luaMutex;

    static DWORD g_moduleBase;
    static void* g_luaInterface;

  

    lua_State* getState() const {
        return m_luaState;
    }

    void LoadScript(const std::string& script, const std::string& name);
    void LoadScriptFile(const std::string& path);
    void LoadScriptFile(FILE* fhandle, const std::string& name);
    void registerFunction(const std::string& name, lua_CFunction func);
    void CallFunction(const std::string& funcName);

    // function that breaks a code
    // Declare the sendToServer method
    static LuaBridge& getInstance();

private:
    static std::unique_ptr<LuaBridge> instance;
};

#endif // LUABRIDGE_H
