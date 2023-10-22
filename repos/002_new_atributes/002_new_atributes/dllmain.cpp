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
typedef double(__thiscall* pfnGetTotalCapacity)(LPVOID g_LocalPlayerPtr);

char* charNamePtr;
unsigned char* LightModePtr;
unsigned char* charOutfitSetPtr;
unsigned char* charOutfitHeadColorPtr;
unsigned char* charOutfitChestColorPtr;
unsigned char* charOutfitLegsColorPtr;
unsigned char* charOutfitBootsColorPtr;
int* charXPosPtr;
int* charYPosPtr;
int* charZPosPtr;
BYTE* charStanceAddress;
BYTE* charFollowMonsterStatusAddress;

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

    pfnGetMaxMana getMaxMana = (pfnGetMaxMana)(g_hGameModule + 0x5AD50);
    pfnGetLevel getLevel = (pfnGetLevel)(g_hGameModule + 0x96F50);

    charNamePtr = (char*)(g_hGameModule + 0x932B50);

    pfnGetHealth getHealth = (pfnGetHealth)(g_hGameModule + 0x96F00);
    pfnGetMaxHealth getMaxHealth = (pfnGetMaxHealth)(g_hGameModule + 0x96F10);
    pfnGetMana getMana = (pfnGetMana)(g_hGameModule + 0x5AD40);
    pfnGetTotalCapacity getTotalCapacity = (pfnGetTotalCapacity)(g_hGameModule + 0x96F30);

    charXPosPtr = (int*)(g_hGameModule + 0x932D9C);
    charYPosPtr = (int*)(g_hGameModule + 0x932DA0);
    charZPosPtr = (int*)(g_hGameModule + 0x932DA4);

    //speed
    //DWORD baseAddr = *(DWORD*)(g_hGameModule + 0x932B70);
    //DWORD offset1 = *(DWORD*)(baseAddr + 0x0);
    //DWORD offset2 = *(DWORD*)(offset1 + 0x1CC);
    //DWORD dynamicAddr = offset2 + 0xB0; // This should now contain 13E2D8C8


    // move char direction
    DWORD baseAddrCharMoveDirection = *(DWORD*)(g_hGameModule + 0x932C70); // Base address: "RealeraDX-1693821795.exe"+00932B70 -> 051CA9F0
    DWORD offset1CharMoveDirection = *(DWORD*)(baseAddrCharMoveDirection + 0x0);           // Follows pointer at 051CA9F0 to reach 15F53B48
    DWORD offset2CharMoveDirection = *(DWORD*)(offset1CharMoveDirection + 0x14);           // Follows pointer at 15F53B48 + 0x14 to reach 14502030
    DWORD dynamicAddrCharMoveDirection = offset2CharMoveDirection + 0x4C;


    // character name and light
    DWORD baseAddress = *(DWORD*)(g_hGameModule + 0x933DA8);   // Step 1
    DWORD offset1 = *(DWORD*)(baseAddress + 0xB8);   // Step 2
    DWORD offset2 = *(DWORD*)(offset1 + 0x84);   // Step 3
    DWORD offset3 = *(DWORD*)(offset2 + 0x0);   // Step 4
    DWORD offset4 = *(DWORD*)(offset3 + 0x0);   // Step 5
    DWORD offset5 = *(DWORD*)(offset4 + 0x36C);   // Step 6
    DWORD offset6 = *(DWORD*)(offset5 + 0x1CC);   // Step 7
    LightModePtr = (unsigned char*)(offset6 + 0xAD);

    // Outfit related dynamic addresses
    charOutfitSetPtr = (unsigned char*)(offset6 + 0x58);
    charOutfitHeadColorPtr = (unsigned char*)(offset6 + 0x60);
    charOutfitChestColorPtr = (unsigned char*)(offset6 + 0x64);
    charOutfitLegsColorPtr = (unsigned char*)(offset6 + 0x68);
    charOutfitBootsColorPtr = (unsigned char*)(offset6 + 0x6C);
    charStanceAddress = (BYTE*)(g_hGameModule + 0x932B24);
    charFollowMonsterStatusAddress = (BYTE*)(g_hGameModule + 0x932B28);
    int* currentBattleTargetIDPtr = (int*)(g_hGameModule + 0x932AA4);

    // Calculate the dynamic address for lightMode based on characterName's dynamic address



    Sleep(3000);

    while (1) {

        int charXPos = *charXPosPtr;
        int charYPos = *charYPosPtr;
        int charZPos = *charZPosPtr;
        std::string charName = charNamePtr;
        //int valueAtDynamicAddress = *(int*)(dynamicAddr);
        BYTE charMoveDirection = *(BYTE*)(dynamicAddrCharMoveDirection);
        int LightMode = static_cast<int>(*LightModePtr);
        int charOutfitSet = static_cast<int>(*charOutfitSetPtr);
        int charOutfitHeadColor = static_cast<int>(*charOutfitHeadColorPtr);
        int charOutfitChestColor = static_cast<int>(*charOutfitChestColorPtr);
        int charOutfitLegsColor = static_cast<int>(*charOutfitLegsColorPtr);
        int charOutfitBootsColor = static_cast<int>(*charOutfitBootsColorPtr);
        BYTE charStance = *charStanceAddress;
        BYTE charFollowMonsterStatus = *charFollowMonsterStatusAddress;
        int currentBattleTargetID = *currentBattleTargetIDPtr;

        system("cls"); // Clear the console before every loop


        // Create a json object

        nlohmann::json gameData;
        gameData["Game_Base"] = g_hGameModule;
        gameData["Game_Pointer"] = (int)g_Game;

        gameData["Character_Name"] = charName;
        
        gameData["Health"] = { {"current", (int)getHealth(g_Player)}, {"max", (int)getMaxHealth(g_Player)} };
        gameData["Mana"] = { {"current", (int)getMana(g_Player)}, {"max", (int)getMaxMana(g_Player)} };
        gameData["Level"] = (int)getLevel(g_Player);
        gameData["Total_Capacity"] = (int)getTotalCapacity(g_Player);
       // gameData["Char_Speed"] = valueAtDynamicAddress;

        gameData["Char_X_Position"] = charXPos;
        gameData["Char_Y_Position"] = charYPos;
        gameData["Char_Z_Position"] = charZPos;
        gameData["Character_Move_Direction"] = charMoveDirection;
        gameData["Light_mode"] = LightMode;
        gameData["charOutfit"] = { 
            {"charOutfitSet", charOutfitSet},
            {"charOutfitHeadColor", charOutfitHeadColor},
            {"charOutfitChestColor", charOutfitChestColor},
            {"charOutfitLegsColor", charOutfitLegsColor},
            {"charOutfitBootsColor", charOutfitBootsColor} 
        };
        gameData["Char_Stance"] = charStance;
        gameData["Char_FollowMonsterStatus"] = charFollowMonsterStatus;
        gameData["Char_Target_Id"] = currentBattleTargetID;


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

        g_Game = (LPDWORD)(g_hGameModule + 0x932AA0);

        CloseHandle(CreateThread(0, 0, (LPTHREAD_START_ROUTINE)BGThread, hModule, 0, 0));
    }
    if (fdwReason == DLL_PROCESS_DETACH)
    {
        // Cleanup, if anyze 3
    }

    return 1;
}