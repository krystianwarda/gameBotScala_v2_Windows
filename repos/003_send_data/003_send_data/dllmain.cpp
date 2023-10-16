#include "pch.h"
#include <Windows.h>
#include <iostream>
#include <TlHelp32.h>
#include <string>


bool IsValidMemoryAddress(void* address) {
    MEMORY_BASIC_INFORMATION mbi;
    if (VirtualQuery(address, &mbi, sizeof(mbi))) {
        DWORD mask = PAGE_READONLY | PAGE_READWRITE | PAGE_WRITECOPY | PAGE_EXECUTE_READ | PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY;
        bool isReadable = (mbi.Protect & mask) != 0;
        return isReadable && mbi.State == MEM_COMMIT;
    }
    return false;
}

// Define the Item class as described earlier
class Item {
public:
    int m_position_x;
    int m_position_y;
    short m_position_z;
    unsigned short m_datId;
    int itemId;

    Item(int id) : itemId(id) {}
};

typedef void* (__thiscall* _GetLocalPlayerFunc)(void* gamePtr);
typedef void(__thiscall* _SetInventoryItemFunc)(void* localPlayerPtr, int slot, const Item& item);
typedef void* (__thiscall* _GetCreatureByIdFunc)(void* mapPtr, int creatureId);  // typedef for getCreatureById

_GetLocalPlayerFunc GetLocalPlayerFunc;
_SetInventoryItemFunc SetInventoryItemFunc;
_GetCreatureByIdFunc GetCreatureByIdFunc;  // Function pointer instance

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
    GetLocalPlayerFunc = (_GetLocalPlayerFunc)(moduleBase + 0x5AE30);
    GetCreatureByIdFunc = (_GetCreatureByIdFunc)(moduleBase + 0x14DE10); // Point to the correct offset for getCreatureById

    while (true)
    {
        if (GetAsyncKeyState(VK_END) & 1)
        {
            break;
        }
        if (GetAsyncKeyState(VK_NUMPAD2) & 1)
        {
            std::cout << "NUMPAD2 pressed" << std::endl;

            void* mapPtr = (void*)(moduleBase + 0x932AF0); // Assuming this is the address of the map object
            int creatureId = 234442120; // Given creature ID

            if (mapPtr) // Always check if the map pointer is valid
            {
                void* creaturePtr = GetCreatureByIdFunc(mapPtr, creatureId);
                if (creaturePtr && IsValidMemoryAddress(creaturePtr) && IsValidMemoryAddress((void*)((uintptr_t)creaturePtr + 0x08)) && IsValidMemoryAddress((void*)((uintptr_t)creaturePtr + 0x10)) && IsValidMemoryAddress((void*)((uintptr_t)creaturePtr + 0x14))) {
          
                    int x = *((int*)((uintptr_t)creaturePtr + 0x08)); // Hypothetical offset for x
                    int y = *((int*)((uintptr_t)creaturePtr + 0x10)); // Given offset for y
                    int z = *((int*)((uintptr_t)creaturePtr + 0x14)); // Given offset for z

                    std::cout << "Creature Coordinates: (" << x << ", " << y << ", " << z << ")" << std::endl;
                    Sleep(500); // Sleep for half a second
                }
                else
                {
                    std::cout << "Creature not found!" << std::endl;
                }
            }
            else
            {
                std::cout << "Map pointer is invalid!" << std::endl;
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
