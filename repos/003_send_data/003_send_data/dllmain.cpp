#include "pch.h"
#include <Windows.h>
#include <iostream>
#include <TlHelp32.h>
#include <string>

typedef void(__thiscall* _SayFunc)(void* g_game_ptr, const std::string& msg);
_SayFunc SayFunc;

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
                if (wcsstr(pe32.szExeFile, L"eale")) // Check if the process name contains "eale"
                {
                    moduleBase = (uintptr_t)GetModuleHandleW(pe32.szExeFile); // Although this is still questionable.
                    break; // Exit the loop once we find the desired process
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

    SayFunc = (_SayFunc)(moduleBase + 0xF7590);

    while (true)
    {
        if (GetAsyncKeyState(VK_END) & 1)
        {
            break;
        }

        if (GetAsyncKeyState(VK_NUMPAD2) & 1)
        {
            std::cout << "Button pressed" << std::endl;
            SayFunc((void*)(moduleBase + 0x933080), "Hello");
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
