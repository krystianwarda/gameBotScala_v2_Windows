#include "pch.h"
#include <Windows.h>
#include <iostream>
#include <TlHelp32.h>
#include <string>

// Based on the function signature provided
typedef void(__thiscall* _SetInventoryItem)(void* localPlayer, int inventory, void* itemPtr);

_SetInventoryItem SetInventoryItem;

DWORD WINAPI HackThread(HMODULE hModule)
{
    AllocConsole();
    std::cout << "Hello there!\n";

    uintptr_t moduleBase = 0;
    HANDLE hSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnapshot != INVALID_HANDLE_VALUE)
    {
        PROCESSENTRY32 pe32;
        pe32.dwSize = sizeof(PROCESSENTRY32);
        if (Process32First(hSnapshot, &pe32))
        {
            do
            {
                if (wcsstr(pe32.szExeFile, L"eale")) // Check if the process name contains "eale"
                {
                    moduleBase = (uintptr_t)GetModuleHandleW(pe32.szExeFile);
                    break; // Exit the loop once we find the desired process
                }
            } while (Process32Next(hSnapshot, &pe32));
        }
        CloseHandle(hSnapshot);
    }

    if (moduleBase == 0)
    {
        std::cout << "Target process not found!\n";
        FreeConsole();
        FreeLibraryAndExitThread(hModule, 0);
        return 0;
    }

    // Assuming m_localPlayer is a pointer to LocalPlayer
    uintptr_t localPlayerPtrAddress = (uintptr_t)(moduleBase + 0x932990); // Based on the address provided by g_game.getLocalPlayer
    void* localPlayerPtr = *(void**)(localPlayerPtrAddress);

    // Address for setInventoryItem function
    SetInventoryItem = (_SetInventoryItem)(moduleBase + 0x927A0);  // Using the address provided for setInventoryItem

    while (true)
    {
        if (GetAsyncKeyState(VK_END) & 1)
        {
            break;
        }

        if (GetAsyncKeyState(VK_NUMPAD2) & 1)
        {
            std::cout << "Button pressed" << std::endl;

            // Set the inventory slot to InventorySlotNecklace (2) for the necklace
            int inventory = 2;

            // Set the item ID to 3081 (assuming this is a pointer)
            void* itemPtr = (void*)3081;

            // Call setInventoryItem with the appropriate arguments
            SetInventoryItem(localPlayerPtr, inventory, itemPtr);
        }
        Sleep(10);
    }

    FreeConsole();
    FreeLibraryAndExitThread(hModule, 0);
    return 0;
}

BOOL APIENTRY DllMain(HMODULE hModule,
    DWORD ul_reason_for_call,
    LPVOID lpReserved
)
{
    switch (ul_reason_for_call)
    {
    case DLL_PROCESS_ATTACH:
    {
        CloseHandle(CreateThread(nullptr, 0, (LPTHREAD_START_ROUTINE)HackThread, hModule, 0, nullptr));
        break;
    }
    case DLL_THREAD_ATTACH:
    case DLL_THREAD_DETACH:
    case DLL_PROCESS_DETACH:
        break;
    }
    return TRUE;
}
