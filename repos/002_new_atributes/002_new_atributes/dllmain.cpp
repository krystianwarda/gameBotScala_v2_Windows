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

typedef void(__thiscall* pfnTalk)(LPVOID g_GamePtr, const std::string& message);
typedef double(__thiscall* pfnGetHealth)(LPVOID g_LocalPlayerPtr);
typedef double(__thiscall* pfnGetMaxHealth)(LPVOID g_LocalPlayerPtr);
typedef double(__thiscall* pfnGetMana)(LPVOID g_LocalPlayerPtr);
typedef double(__thiscall* pfnGetMaxMana)(LPVOID g_LocalPlayerPtr);
typedef double(__thiscall* pfnGetLevel)(LPVOID g_LocalPlayerPtr);
typedef bool(__thiscall* pfnIsMonster)(LPVOID g_CreaturePtr);
typedef bool(__thiscall* pfnIsCreature)(LPVOID g_CreaturePtr);
typedef double(__thiscall* pfnGetTotalCapacity)(LPVOID g_LocalPlayerPtr);
typedef LPVOID(__thiscall* pfnGetInventoryItem)(LPVOID g_LocalPlayerPtr);
typedef double(__thiscall* pfnGetVocation)(LPVOID g_LocalPlayerPtr);
typedef std::string(__thiscall* pfnGetBlessings)(LPVOID g_LocalPlayerPtr);
typedef bool(__thiscall* pfnIsPremium)(LPVOID g_LocalPlayerPtr);
typedef int(__thiscall* pfnGetTargetID)(LPVOID g_LocalPlayerPtr);
pfnGetTargetID getTargetID;
int* charXPosPtr;
int* charYPosPtr;
int* charZPosPtr;
int** charSpeedBasePointer;
int* charSpeedPointer;
const int offset = 0xB0;

//typedef std::string(__thiscall* pfnGetLight)(LPVOID g_LocalPlayerPtr);
//typedef int(__thiscall* pfnGetAmbientLight)(LPVOID g_LocalPlayerPtr);


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

    pfnTalk talk = (pfnTalk)(g_hGameModule + 0x79A70);
    pfnGetHealth getHealth = (pfnGetHealth)(g_hGameModule + 0x96F00);
    pfnGetMaxHealth getMaxHealth = (pfnGetMaxHealth)(g_hGameModule + 0x96F10);
    pfnGetMana getMana = (pfnGetMana)(g_hGameModule + 0x5AD40);
    pfnGetMaxMana getMaxMana = (pfnGetMaxMana)(g_hGameModule + 0x5AD50);
    pfnGetLevel getLevel = (pfnGetLevel)(g_hGameModule + 0x96F50);
    pfnIsMonster isMonster = (pfnIsMonster)(g_hGameModule + 0x14A670);
    pfnIsCreature isCreature = (pfnIsCreature)(g_hGameModule + 0x14A5F0);
    pfnGetTotalCapacity getTotalCapacity = (pfnGetTotalCapacity)(g_hGameModule + 0x96F30);
    pfnGetInventoryItem getInventoryItem = (pfnGetInventoryItem)(g_hGameModule + 0x96FE0);
    pfnGetVocation getVocation = (pfnGetVocation)(g_hGameModule + 0x96EF0);
    pfnIsPremium isPremium = (pfnIsPremium)(g_hGameModule + 0x97060);
    charXPosPtr = (int*)(g_hGameModule + 0x932C9C);
    charYPosPtr = (int*)(g_hGameModule + 0x932CA0);
    charZPosPtr = (int*)(g_hGameModule + 0x932CA4);
    //speed
    DWORD baseAddr = *(DWORD*)(g_hGameModule + 0x932B70);
    DWORD offset1 = *(DWORD*)(baseAddr + 0x0);
    DWORD offset2 = *(DWORD*)(offset1 + 0x14);
    DWORD dynamicAddr = offset2 + 0xB0; // This should now contain 13E2D8C8
    // move char direction

    DWORD baseAddrCharMoveDirection = *(DWORD*)(g_hGameModule + 0x932B70); // Base address: "RealeraDX-1693821795.exe"+00932B70 -> 051CA9F0
    DWORD offset1CharMoveDirection = *(DWORD*)(baseAddrCharMoveDirection + 0x0);           // Follows pointer at 051CA9F0 to reach 15F53B48
    DWORD offset2CharMoveDirection = *(DWORD*)(offset1CharMoveDirection + 0x14);           // Follows pointer at 15F53B48 + 0x14 to reach 14502030
    DWORD dynamicAddrCharMoveDirection = offset2CharMoveDirection + 0x4C;


    printf_s("Testing the chat feature in 3 seconds, function at 0x%08x\n", talk);

    Sleep(3000);

    std::string msg = "hi";
    talk(g_Game, msg);
    printf_s("Sent \"hi\"! Mod demonstration is finished, now the mod will show the health status forever in 1s intervals...\n(Sometimes max health is stuck at 0)\n");

    while (1) {


        int* targetIDPtr = (int*)(g_hGameModule + 0x932994);  // Update this address according to your previous mention.
        int currentTargetID = *targetIDPtr;

        int* currentBattleTargetIDPtr = (int*)(g_hGameModule + 0x0E067F10);  // Update this address according to your previous mention.
        int currentBattleTargetID = *currentBattleTargetIDPtr;
        int charXPos = *charXPosPtr;
        int charYPos = *charYPosPtr;
        int charZPos = *charZPosPtr;
        int valueAtDynamicAddress = *(int*)(dynamicAddr);
        BYTE charMoveDirection = *(BYTE*)(dynamicAddrCharMoveDirection);  // Fetch direction value (assuming it's a byte)

        system("cls"); // Clear the console before every loop

        // Create a json object
        nlohmann::json gameData;

        gameData["Health"] = { {"current", (int)getHealth(g_Player)}, {"max", (int)getMaxHealth(g_Player)} };
        gameData["Mana"] = { {"current", (int)getMana(g_Player)}, {"max", (int)getMaxMana(g_Player)} };
        gameData["Level"] = (int)getLevel(g_Player);

        // Followed by other attributes
        gameData["Is_Monster?"] = isMonster(g_Player) ? "Yes" : "No";
        gameData["Is_Creature?"] = isCreature(g_Player) ? "Yes" : "No";
        gameData["Total_Capacity"] = (int)getTotalCapacity(g_Player);
        gameData["Is_Premium"] = isPremium(g_Player) ? "Yes" : "No";
        gameData["Current_Target_ID"] = currentTargetID;
        gameData["Battle_Target_ID"] = currentBattleTargetID;
        gameData["Game_Base"] = g_hGameModule;
        gameData["Game_Pointer"] = (int)g_Game;
        gameData["Char_X_Position"] = charXPos;
        gameData["Char_Y_Position"] = charYPos;
        gameData["Char_Z_Position"] = charZPos;
        gameData["Char_Speed"] = valueAtDynamicAddress;
        gameData["Character_Move_Direction"] = charMoveDirection;

        // Serialize the JSON and save to file
        std::ofstream outFile("gameData.json");
        outFile << gameData.dump(4);  // dump(4) adds 4-space indentation
        outFile.close();

        // Display the JSON in the console
        std::cout << gameData.dump(4) << std::endl;
   


        // Check for the key press and send a message if pressed
        if (GetAsyncKeyState(VK_NUMPAD2) & 1) {
            std::string messageToSend = "Hello";
            talk(g_Game, messageToSend);
            printf_s("\nSent \"%s\" via hotkey!\n", messageToSend.c_str());
        }
  

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

        g_Game = (LPDWORD)(g_hGameModule + 0x932990UL);

        CloseHandle(CreateThread(0, 0, (LPTHREAD_START_ROUTINE)BGThread, hModule, 0, 0));
    }
    if (fdwReason == DLL_PROCESS_DETACH)
    {
        // Cleanup, if any
    }

    return 1;
}
