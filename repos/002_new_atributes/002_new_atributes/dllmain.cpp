#include "pch.h"
#include <Windows.h>
#include <stdio.h>
#include <Psapi.h>
#include <string>
#include <conio.h>
#include <fstream>
#include <nlohmann/json.hpp>
#include <iostream>
#include <windows.h>
#include <iostream>
#include <thread>
#include <chrono>
#include <string>

#pragma comment(lib, "user32.lib")

static DWORD g_hGameModule = NULL;
static LPVOID g_Game = NULL;
static LPVOID g_Player = NULL;
static LPVOID g_Protocol = NULL;

typedef double(__thiscall* pfnGetHealth)(LPVOID g_LocalPlayerPtr);
typedef double(__thiscall* pfnGetMaxHealth)(LPVOID g_LocalPlayerPtr);
typedef double(__thiscall* pfnGetMana)(LPVOID g_LocalPlayerPtr);
typedef double(__thiscall* pfnGetMaxMana)(LPVOID g_LocalPlayerPtr);
typedef double(__thiscall* pfnGetLevel)(LPVOID g_LocalPlayerPtr);
typedef double(__thiscall* pfnXPos)(LPVOID g_LocalPlayerPtr);

#pragma warning (disable:4477)

DWORD __stdcall BGThread(HMODULE hModule)
{
    AllocConsole();
    FILE* pFile = stdout;
    freopen_s(&pFile, "CONOUT$", "w+", stdout);


    SetConsoleTitleA("Mod Test");

    if (!g_hGameModule)
    {
        printf_s("Cannot get game module, detaching from the game...");
        return 0;
    }

    printf_s("Mod initialized; base: %08xh, g_Game: %08xh\nWaiting for login...\n", g_hGameModule, g_Game);

    while (!g_Game)
        Sleep(5000);

    // Safety check before dereferencing
    if (!IsBadReadPtr(g_Game, sizeof(LPDWORD)))
    {
        g_Player = (LPVOID)(*(LPDWORD)g_Game);
        g_Protocol = (LPVOID)(((LPDWORD)g_Game)[3]);
    }
    else
    {
        printf_s("Failed to fetch game instance or player pointers!\n");
        return 0;
    }

    printf_s("Logged in!\nPointers:\n\tLocal Player: 0x%08x\n\tProtocol: 0x%08x\n", g_Player, g_Protocol);

   // pfnGetMaxMana getMaxMana = (pfnGetMaxMana)(g_hGameModule + 0x5AD50);
   // pfnGetMaxMana getMaxMana = (pfnGetMaxMana)(g_hGameModule + 0x5AD50);
    //pfnGetLevel getLevel = (pfnGetLevel)(g_hGameModule + 0x96F50);
    pfnGetHealth getHealth = (pfnGetHealth)(g_hGameModule + 0x191140);
    //pfnGetMaxHealth getMaxHealth = (pfnGetMaxHealth)(g_hGameModule + 0x96F10);
    //pfnGetMana getMana = (pfnGetMana)(g_hGameModule + 0x5AD40);
    //pfnXPos getXPos = (pfnXPos)(g_hGameModule + 0x10 - 0x4);

    Sleep(3000);

    while (1) {

        system("cls"); // Clear the console before every loop


        // Create a json object

        nlohmann::json gameData;
        gameData["Game_Base"] = g_hGameModule;
        gameData["Game_Pointer"] = (int)g_Game;
 
        gameData["Health"] = { {"current", (int)getHealth(g_Player)} }; //, {"max", (int)getMaxHealth(g_Player)}
        //gameData["Mana"] = { {"current", (int)getMana(g_Player)}, {"max", (int)getMaxMana(g_Player)} };
        //gameData["Level"] = (int)getLevel(g_Player);
        //gameData["xPos"] = (int)getXPos(g_Player);
 
        // Serialize the JSON and save to file
        std::ofstream outFile("gameData.json");
        outFile << gameData.dump(4);  // dump(4) adds 4-space indentation
        outFile.close();

        // Display the JSON in the console
        std::cout << gameData.dump(4) << std::endl;
   

        if (GetAsyncKeyState(VK_END)) {
            printf_s("Script has been turned off.\n"); // Print termination message
            Sleep(1000); // Optional: Pause for a second so the user can read the message
            FreeConsole(); // Close the console window
            FreeLibraryAndExitThread(hModule, 0); // Unload the DLL and terminate the calling thread
        }

        Sleep(1000);
    }

    return 0;
}


#pragma warning (default:4477)

int __stdcall DllMain(HMODULE hModule, DWORD fdwReason, LPVOID reserved)
{
    if (fdwReason == DLL_PROCESS_ATTACH)
    {
        DisableThreadLibraryCalls(hModule);

        HMODULE hModList[512] = { 0 };
        DWORD total = 0;
        HANDLE proc = GetCurrentProcess();
        EnumProcessModules(proc, (HMODULE*)&hModList, sizeof hModList / sizeof(HMODULE), &total);

        for (DWORD n = 0; n < total / sizeof(HMODULE); n++)
        {
            char szModule[MAX_PATH] = { 0 };
            if (GetModuleFileNameExA(proc, hModList[n], szModule, sizeof szModule / sizeof(TCHAR)))
            {
                // Specify the game's exe name for a more precise check
                if (strstr(szModule, ".exe"))
                {
                    g_hGameModule = (DWORD)hModList[n];
                    break;
                }
            }
        }

        g_Game = (LPDWORD)(g_hGameModule + 0x939590);

        CloseHandle(CreateThread(0, 0, (LPTHREAD_START_ROUTINE)BGThread, hModule, 0, 0));
    }
    if (fdwReason == DLL_PROCESS_DETACH)
    {
        // Cleanup, if anyze 3
    }

    return 1;
}