#include <winsock2.h>
#include <Windows.h>

#include <TlHelp32.h>
#include <DbgHelp.h>
#include <iostream>
#include <vector>
#include <string>
#include <functional>

#include "include.h"
#include "hook.h"
#include "LuaBridge.h"


#pragma comment(lib, "dbghelp.lib")

std::unique_ptr<LuaBridge> LuaBridge::instance;

using std::vector;
using std::string;

DWORD WINAPI HackThread(HMODULE hModule);

typedef int(__stdcall* pfnLuaILoadBuffer)(DWORD code, DWORD source_path);
int __stdcall hookLuaILoadBuffer(DWORD codePtr, DWORD source_pathPtr);
//pfnLuaILoadBuffer g_fnLuaILoadBuffer = 0;


BOOL APIENTRY DllMain(HMODULE hModule,
    DWORD  ul_reason_for_call,
    LPVOID lpReserved
)
{
    switch (ul_reason_for_call)
    {
    case DLL_PROCESS_ATTACH:
    {
        HANDLE thread = CreateThread(nullptr, 0, (LPTHREAD_START_ROUTINE)HackThread, hModule, 0, nullptr);
        if (thread != NULL) {
            CloseHandle(thread);
        }
        break;
    }
    case DLL_THREAD_ATTACH:
    case DLL_THREAD_DETACH:
    case DLL_PROCESS_DETACH:
        break;
    }
    return TRUE;
}


static uintptr_t moduleBase = 0;
DWORD WINAPI HackThread(HMODULE hModule)
{
    AllocConsole();

    // enable colors in console window
    DWORD dwConsoleMode = 0;
    HANDLE hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    GetConsoleMode(hStdOutput, &dwConsoleMode);
    dwConsoleMode |= ENABLE_PROCESSED_OUTPUT | ENABLE_VIRTUAL_TERMINAL_PROCESSING;
    SetConsoleMode(hStdOutput, dwConsoleMode);

    FILE* f;
    freopen_s(&f, "CONIN$", "r", stdin);
    freopen_s(&f, "CONOUT$", "w", stdout);

    SetConsoleTitleA("Lua mod console (type \"R\" to reload custom lua files)");
        

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
                //if (wcsstr(pe32.szExeFile, L"otclient"))
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
        if (f) fclose(f);
        FreeConsole();
        FreeLibraryAndExitThread(hModule, 0);
        return 0;
    }

    PIMAGE_NT_HEADERS pnth = ImageNtHeader((PVOID)moduleBase);

    PIMAGE_SECTION_HEADER ptsh = ImageRvaToSection(pnth, (PVOID)moduleBase, pnth->OptionalHeader.BaseOfCode);
    PIMAGE_SECTION_HEADER pdsh = ImageRvaToSection(pnth, (PVOID)moduleBase, pnth->OptionalHeader.BaseOfData);

    PCHAR s_text = reinterpret_cast<PCHAR>(moduleBase + pnth->OptionalHeader.BaseOfCode);
    PCHAR s_data = reinterpret_cast<PCHAR>(moduleBase + pnth->OptionalHeader.BaseOfData);

    size_t pBit32, pPush;
    for (pBit32 = 0; pBit32 < pdsh->SizeOfRawData; pBit32++)
        if (!memcmp(s_data + pBit32, "bit32", 6))
            break;

    if (pBit32 == pdsh->SizeOfRawData)
    {
        MessageBoxA(nullptr, "Cannot find lua string pattern", "Application Error", MB_ICONERROR);

        if (f) fclose(f);
        FreeConsole();
        FreeLibraryAndExitThread(hModule, 0);
        return 0;
    }
     
    pBit32 += (size_t)s_data;

    unsigned char pushPattern[7] = { 0x68, 0, 0, 0, 0, 0xFF, 0x35 };
    *((DWORD*)&pushPattern[1]) = pBit32;
    for (pPush = 0; pPush < ptsh->SizeOfRawData; pPush++)
        if (!memcmp(s_text + pPush, pushPattern, sizeof pushPattern))
        {
            pPush += sizeof pushPattern;
            break;
        }

    if (pPush == ptsh->SizeOfRawData)
    {
        MessageBoxA(nullptr, "Cannot find lua pointer pattern", "Application Error", MB_ICONERROR);

        if (f) fclose(f);
        FreeConsole();
        FreeLibraryAndExitThread(hModule, 0);
        return 0;
    }

    LuaBridge::g_moduleBase = moduleBase;
    LuaBridge::g_luaInterface = reinterpret_cast<PVOID>(*reinterpret_cast<DWORD*>(s_text + pPush));

    std::cout << std::hex << "Module base: 0x" << moduleBase << ", Lua Interface: 0x" << LuaBridge::g_luaInterface << std::dec << std::endl;

    //g_fnLuaILoadBuffer = (pfnLuaILoadBuffer)Hook((void*)(moduleBase + 0x3112C0), hookLuaILoadBuffer);

    LuaBridge lua;
loadfiles:

    WIN32_FIND_DATAA wfd{};

    HANDLE hFF = FindFirstFileA("mods\\*.lua", &wfd);
    if (hFF != INVALID_HANDLE_VALUE)
    {
        do
        {
            if (wfd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)
                continue;

            string filePath(MAX_PATH, 0);
            sprintf_s(&filePath[0], MAX_PATH, "mods\\%s", wfd.cFileName);

            lua.LoadScriptFile(filePath);

        } while (FindNextFileA(hFF, &wfd));

        FindClose(hFF);
    }

    while (1)
    {
        if ((getchar() & 'R') == 'R') // allows both "R" and "r"
            goto loadfiles;
        Sleep(250);
    }

    fclose(f);
    FreeConsole();
    FreeLibraryAndExitThread(hModule, 0);
    return 0;
}
