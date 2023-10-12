#include "pch.h"
#include <Windows.h>
#include <iostream>
#include <TlHelp32.h>
#include <string>

class Item {
public:
    int itemId;
    Item(int id) : itemId(id) {}
};

typedef void* (__thiscall* _GetLocalPlayerFunc)(void* gamePtr);
typedef void(__thiscall* _SetInventoryItemFunc)(void* localPlayerPtr, int slot, const Item& item);

_GetLocalPlayerFunc GetLocalPlayerFunc;
_SetInventoryItemFunc SetInventoryItemFunc;

DWORD WINAPI HackThread(HMODULE hModule)
{
    AllocConsole();
    FILE* f;
    freopen_s(&f, "CONOUT$", "w", stdout);

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
                if (wcsstr(pe32.szExeFile, L"eale"))
                {
                    moduleBase = (uintptr_t)GetModuleHandleW(pe32.szExeFile);
                    break;
                }
            } while (Process32Next(hSnapshot, &pe32));
        }
        CloseHandle(hSnapshot);
    }

    if (moduleBase == 0)
    {
        std::cout << "Target process not found!\n";
        fclose(f);
        FreeConsole();
        FreeLibraryAndExitThread(hModule, 0);
        return 0;
    }

    SetInventoryItemFunc = (_SetInventoryItemFunc)(moduleBase + 0x927A0);
    GetLocalPlayerFunc = (_GetLocalPlayerFunc)(moduleBase + 0x5AE30); // Use the correct offset for getLocalPlayer

    while (true)
    {
        if (GetAsyncKeyState(VK_END) & 1)
        {
            break;
        }

        if (GetAsyncKeyState(VK_NUMPAD2) & 1)
        {
            std::cout << "Button pressed" << std::endl;
            Item myItem(2920);

            void* localPlayer = GetLocalPlayerFunc((void*)(moduleBase + 0x932990));
            if (localPlayer)
            {
                SetInventoryItemFunc(localPlayer, 5, myItem);
            }
        }
        Sleep(10);
    }

    fclose(f);
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
