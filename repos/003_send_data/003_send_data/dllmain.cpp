#include "pch.h"
#include <Windows.h>
#include <iostream>
#include <TlHelp32.h>
#include <string>

bool CanReadMemory(void* address) {
    __try {
        volatile char check = *(char*)address;
        return true;
    }
    __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

class Item {
public:
    int m_position_x;
    int m_position_y;
    short m_position_z;
    unsigned short m_datId;
    int itemId;

    Item(int id) : itemId(id) {}
};

typedef void* (__thiscall* _GetCreatureByIdFunc)(void* mapPtr, int creatureId);
typedef void(__thiscall* _SetInventoryItemFunc)(void* localPlayerPtr, int slot, const Item& item);
typedef void* (__thiscall* _GetLocalPlayerFunc)(void* gamePtr);

_GetLocalPlayerFunc GetLocalPlayerFunc;
_SetInventoryItemFunc SetInventoryItemFunc;
_GetCreatureByIdFunc GetCreatureByIdFunc;

const uintptr_t OFFSET_SetInventoryItemFunc = 0x927A0;
const uintptr_t OFFSET_GetLocalPlayerFunc = 0x5AE30;
const uintptr_t OFFSET_GetCreatureByIdFunc = 0x14DE10;
const uintptr_t OFFSET_g_map = 0x932AF0;

DWORD WINAPI HackThread(HMODULE hModule) {
    AllocConsole();
    FILE* f;
    freopen_s(&f, "CONOUT$", "w", stdout);

    std::cout << "Hello there!\n";

    uintptr_t moduleBase = 0;
    HANDLE hSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnapshot != INVALID_HANDLE_VALUE) {
        PROCESSENTRY32 pe32;
        pe32.dwSize = sizeof(PROCESSENTRY32);
        if (Process32First(hSnapshot, &pe32)) {
            do {
                if (wcsstr(pe32.szExeFile, L"eale")) {
                    moduleBase = (uintptr_t)GetModuleHandleW(pe32.szExeFile);
                    break;
                }
            } while (Process32Next(hSnapshot, &pe32));
        }
        CloseHandle(hSnapshot);
    }

    if (moduleBase == 0) {
        std::cout << "Target process not found!\n";
        fclose(f);
        FreeConsole();
        FreeLibraryAndExitThread(hModule, 0);
        return 0;
    }

    GetLocalPlayerFunc = (_GetLocalPlayerFunc)(moduleBase + OFFSET_GetLocalPlayerFunc);
    SetInventoryItemFunc = (_SetInventoryItemFunc)(moduleBase + OFFSET_SetInventoryItemFunc);
    GetCreatureByIdFunc = (_GetCreatureByIdFunc)(moduleBase + OFFSET_GetCreatureByIdFunc);

    while (true) {
        if (GetAsyncKeyState(VK_END) & 1) {
            break;
        }
        if (GetAsyncKeyState(VK_NUMPAD2) & 1) {
            std::cout << "NUMPAD2 pressed" << std::endl;

            void* mapPtr = (void*)(moduleBase + OFFSET_g_map);

            int creatureId = 331840776;

            if (mapPtr && GetCreatureByIdFunc) {
                void* creaturePtr = GetCreatureByIdFunc(mapPtr, creatureId);
                if (creaturePtr && CanReadMemory(creaturePtr)) {
                    int x = *((int*)((uintptr_t)creaturePtr + 0x08));
                    int y = *((int*)((uintptr_t)creaturePtr + 0x10));
                    int z = *((int*)((uintptr_t)creaturePtr + 0x14));

                    std::cout << "Creature Coordinates: (" << x << ", " << y << ", " << z << ")" << std::endl;
                    Sleep(500);
                }
                else {
                    std::cout << "Creature not found or invalid memory!" << std::endl;
                }
            }
            else {
                std::cout << "Map pointer is invalid or function not initialized!" << std::endl;
            }
        }
        Sleep(10);
    }

    fclose(f);
    FreeConsole();
    FreeLibraryAndExitThread(hModule, 0);
    return 0;
}

BOOL APIENTRY DllMain(HMODULE hModule, DWORD ul_reason_for_call, LPVOID lpReserved) {
    switch (ul_reason_for_call) {
    case DLL_PROCESS_ATTACH:
        CloseHandle(CreateThread(nullptr, 0, (LPTHREAD_START_ROUTINE)HackThread, hModule, 0, nullptr));
        break;
    case DLL_THREAD_ATTACH:
    case DLL_THREAD_DETACH:
    case DLL_PROCESS_DETACH:
        break;
    }
    return TRUE;
}
