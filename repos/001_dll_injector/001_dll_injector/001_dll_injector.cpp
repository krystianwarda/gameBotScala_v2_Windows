#include <Windows.h>
#include <TlHelp32.h>
#include <iostream>

bool InjectDLL(const wchar_t* dllPath, const wchar_t* processSubstring)
{
    DWORD processId = 0;
    HANDLE hSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnapshot == INVALID_HANDLE_VALUE)
        return false;

    PROCESSENTRY32 pe32;
    pe32.dwSize = sizeof(PROCESSENTRY32);
    if (!Process32First(hSnapshot, &pe32))
    {
        CloseHandle(hSnapshot);
        return false;
    }

    do
    {
        std::wcout << L"Checking process: " << pe32.szExeFile << L" PID: " << pe32.th32ProcessID << std::endl;

        // Let's ensure the substring ends with ".exe"
        if (wcsstr(pe32.szExeFile, processSubstring) && wcsstr(pe32.szExeFile, L".exe"))
        {
            processId = pe32.th32ProcessID;
            std::wcout << L"Found target process: " << pe32.szExeFile << L" with PID: " << processId << std::endl;
            break;
        }
    } while (Process32Next(hSnapshot, &pe32));

    CloseHandle(hSnapshot);

    if (!processId)
    {
        std::cout << "Target process not found!" << std::endl;
        return false;
    }

    HANDLE hProcess = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION |
        PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ,
        FALSE, processId);
    if (!hProcess)
    {
        DWORD err = GetLastError();
        std::cout << "Failed to open target process! Error code: " << err << std::endl;
        return false;
    }

    void* pDllPath = VirtualAllocEx(hProcess, 0, (wcslen(dllPath) + 1) * sizeof(wchar_t), MEM_COMMIT, PAGE_READWRITE);
    if (!pDllPath)
    {
        CloseHandle(hProcess);
        std::cout << "Failed to allocate memory in target process!" << std::endl;
        return false;
    }

    if (!WriteProcessMemory(hProcess, pDllPath, dllPath, (wcslen(dllPath) + 1) * sizeof(wchar_t), NULL))
    {
        VirtualFreeEx(hProcess, pDllPath, 0, MEM_RELEASE);
        CloseHandle(hProcess);
        std::cout << "Failed to write to target process memory!" << std::endl;
        return false;
    }

    // Use LoadLibraryW instead of LoadLibraryA
    HANDLE hThread = CreateRemoteThread(hProcess, 0, 0,
        (LPTHREAD_START_ROUTINE)GetProcAddress(GetModuleHandleW(L"Kernel32.dll"),
            "LoadLibraryW"), pDllPath, 0, 0);
    if (!hThread)
    {
        VirtualFreeEx(hProcess, pDllPath, 0, MEM_RELEASE);
        CloseHandle(hProcess);
        std::cout << "Failed to create remote thread in target process!" << std::endl;
        return false;
    }

    WaitForSingleObject(hThread, INFINITE);

    CloseHandle(hThread);
    VirtualFreeEx(hProcess, pDllPath, 0, MEM_RELEASE);
    CloseHandle(hProcess);
    return true;
}

int main()
{
    const wchar_t* dllPath = L"C:\\MyLibraries\\old_is_new_dll32.dll";
    const wchar_t* processSubstring = L"eale";

    if (InjectDLL(dllPath, processSubstring))
        std::cout << "DLL Injected successfully!" << std::endl;
    else
        std::cout << "DLL Injection failed!" << std::endl;

    Sleep(150000);
    return 0;
}
