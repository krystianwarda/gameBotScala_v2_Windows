#include "pch.h"
#include <Windows.h>
#include <iostream>
#include <TlHelp32.h>
#include <string>

// Define the function prototype
typedef void(__thiscall* _GameWalkFunc)(void* ggame_ptr, int steps);
_GameWalkFunc GameWalkFunc;

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

    // Dynamic Address calculations based on provided steps
    DWORD baseAddressDynamicAddress = *(DWORD*)(moduleBase + 0x933CA0);   // Step 1
    DWORD offset1DynamicAddress = *(DWORD*)(baseAddressDynamicAddress + 0xB8);   // Step 2
    DWORD offset2DynamicAddress = *(DWORD*)(offset1DynamicAddress + 0x84);   // Step 3
    DWORD offset3DynamicAddress = *(DWORD*)(offset2DynamicAddress + 0x0);   // Step 4
    DWORD offset4DynamicAddress = *(DWORD*)(offset3DynamicAddress + 0x0);   // Step 5
    DWORD offset5DynamicAddress = *(DWORD*)(offset4DynamicAddress + 0x36C);   // Step 6
    DWORD offset6DynamicAddress = *(DWORD*)(offset5DynamicAddress + 0x1CC);   // Step 7
    DWORD dynamicAddrDynamicAddress = offset6DynamicAddress + 0x114;   // Step 8
    DWORD dynamicLightMode = dynamicAddrDynamicAddress - 0x67;   // Step 9

    while (true)
    {
        if (GetAsyncKeyState(VK_END) & 1)
        {
            break;
        }

        if (GetAsyncKeyState(VK_NUMPAD2) & 1)
        {
            std::cout << "Button pressed" << std::endl;
            *(BYTE*)(dynamicLightMode) = 10;
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
