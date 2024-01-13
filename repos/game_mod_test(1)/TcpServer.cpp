#include "TcpServer.hpp"
#include <WS2tcpip.h>
#include <iostream>

TcpServer::TcpServer(const string &local_ip, unsigned short port, bool verbose)
: m_isRunning(false), m_sckListen(INVALID_SOCKET), m_verbose(verbose)
{
    InitializeCriticalSection(&this->m_mutex);
    
    WSADATA wsaData{};
    int result = WSAStartup(0x0202, &wsaData);
    if (result != 0) {
        std::cerr << "WSAStartup failed: Error " << result << std::endl;
        throw std::runtime_error("WSAStartup failed with error: " + std::to_string(result));
    }

    // Create socket
    this->m_sckListen = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (this->m_sckListen == INVALID_SOCKET) {
        WSACleanup();
        throw std::runtime_error("Socket creation failed with error: " + std::to_string(WSAGetLastError()));
    }

    // Define server structure
    sockaddr_in serverAddress;
    serverAddress.sin_family = AF_INET;
    serverAddress.sin_port = htons(port);

    // Convert IP address from string to binary form
    result = inet_pton(AF_INET, local_ip.c_str(), &serverAddress.sin_addr);
    if (result <= 0) {
        closesocket(this->m_sckListen);
        WSACleanup();
        throw std::runtime_error("Invalid address/Address not supported");
    }
    
    result = bind(this->m_sckListen, (struct sockaddr*)&serverAddress, sizeof(serverAddress));
    if (result == SOCKET_ERROR) {
        closesocket(this->m_sckListen);
        WSACleanup();
        throw std::runtime_error("Binding server socket failed with error: " + std::to_string(WSAGetLastError()));
    }
}

TcpServer::~TcpServer()
{
    if (this->Stop())
        WSACleanup();

    DeleteCriticalSection(&this->m_mutex);
}

bool TcpServer::PollRequest(SOCKET& sckFd, sockaddr_in& clientAddr, std::string& requestStr)
{
    bool exitQuery = false;
    do
    {
        // clean the list from invalid sockets
        this->m_rvClients.erase(
            std::remove_if(
                this->m_rvClients.begin(),
                this->m_rvClients.end(),
                [](WSAPOLLFD pfd) -> bool
                {
                    return pfd.fd == INVALID_SOCKET;
                }),
            this->m_rvClients.end());

        // now poll the list
        int result = WSAPoll(this->m_rvClients.data(), this->m_rvClients.size(), 10);
        if (result == SOCKET_ERROR)
        {
            throw std::runtime_error("Server polling failed with error: " + std::to_string(WSAGetLastError()));
        }

        // examine results
        EnterCriticalSection(&this->m_mutex);
        
        for (auto& poll : this->m_rvClients)
        {
            if (poll.revents & POLLRDNORM)
            {
                if (poll.fd == this->m_sckListen)
                {
                    sockaddr_in cliAddr{};
                    int csize = sizeof sockaddr_in;
                    SOCKET cli = accept(poll.fd, (sockaddr *)&cliAddr, &csize);
                    if (cli == SOCKET_ERROR)
                        throw std::runtime_error("Client first connection failed with error: " + std::to_string(WSAGetLastError()));

                    WSAPOLLFD pfd{};
                    pfd.fd = cli;
                    pfd.events = POLLRDNORM;

                    this->m_rvClients.push_back(pfd);

                    if (this->m_verbose) std::cout << "[#" << cli << "] New connection" << std::endl;
                    
                    // you can send an initial message as follows:
                    // json j = {{"__status", "initial"}, {"message": "a message"}}
                    // this->SendOn(cli, j.dump())
                    // this->ShutdownClient(cli, false);
                    break;
                }
                else
                {
                    size_t length = 0;
                    int wle = 0;
                    if (this->m_verbose) std::cout << "[#" << poll.fd << "] Data received" << std::endl;

                    sckFd = poll.fd;

                    int received = recv(poll.fd, (char *)&length, sizeof length, 0);
                    wle = WSAGetLastError();
                    if (wle == 0 && received > 0)
                    {
                        requestStr.resize(length);
                        size_t pos = 0;
                        while (1)
                        {
                            received = recv(poll.fd, &requestStr[pos], length - pos, 0);
                            wle = WSAGetLastError();
                            if (wle == 0 && received > 0)
                            {
                                if (pos + received < length)
                                {
                                    pos += received;
                                    continue;
                                }
                                
                                int cal = sizeof sockaddr_in;
                                getpeername(poll.fd, (sockaddr *)&clientAddr, &cal);
                                exitQuery = true;
                                break;
                            }
                            else if (received == 0)
                            {
                                poll.revents |= POLLHUP;
                                break;
                            }
                            else if (wle != WSAEWOULDBLOCK)
                            {
                                poll.revents |= POLLERR;
                                break;
                            }
                        }
                    }
                    else if (received == 0)
                        poll.revents |= POLLHUP;
                    else if (wle != WSAEWOULDBLOCK)
                        poll.revents |= POLLERR;
                }
            }
            
            if (poll.fd != this->m_sckListen)
            {
                if (poll.revents & (POLLHUP | POLLERR))
                {
                    // POLLHUP: set - client disconnected, unset - receive error
                    // mark the socket invalid so in the next PollRequest() call it will be removed
                    if (this->m_verbose) std::cout << "[#" << poll.fd << "] " << ((poll.revents & POLLHUP) ? "Disconnected" : "Error") << std::endl;

                    closesocket(poll.fd);
                    poll.fd = INVALID_SOCKET;
                }
                
                if (poll.revents & POLLNVAL)
                    poll.fd = INVALID_SOCKET;
            }
        }

        LeaveCriticalSection(&this->m_mutex);
    } while (!exitQuery);
    return true;
}

bool TcpServer::SendOn(const SOCKET &sckFd, const std::string &data)
{
    auto p = std::find_if(this->m_rvClients.begin(), this->m_rvClients.end(), [sckFd](WSAPOLLFD pfd) -> bool
                          { return pfd.fd == sckFd; });

    if (p == this->m_rvClients.end())
    {
        if (this->m_verbose) std::cout << "Cannot send data on socket #" << sckFd << ": Client socket does not exist" << std::endl;
        return false;
    }

    if ((*p).revents & (POLLHUP | POLLERR | POLLNVAL))
    {
        if (this->m_verbose) std::cout << "Cannot send data on socket #" << sckFd << ": Socket is disconnected, invalid, or in error state" << std::endl;
        return false;
    }

    size_t pos = 0;
    size_t length = data.size();
    int sent = send(sckFd, (char *)&length, sizeof length, 0);
    int wle = WSAGetLastError();

    //sent = send(sckFd, &data[pos], length - pos, 0);
    //wle = WSAGetLastError();
    if (wle == 0 && sent > 0)
    {
        while (1)
        {
            sent = send(sckFd, &data[pos], length - pos, 0);
            wle = WSAGetLastError();
            if (wle == 0 && sent > 0)
            {
                if (sent + pos < length)
                {
                    pos += sent;
                    continue;
                }

                return true;
            }
            if (sent == 0)
            {
                (*p).revents |= POLLHUP;
                break;
            }
            if (wle != WSAEWOULDBLOCK)
            {
                (*p).revents |= POLLERR;
                break;
            }
        }
    }
    if (sent == 0)
        (*p).revents |= POLLHUP;
    if (sent != sizeof length && wle != WSAEWOULDBLOCK)
        (*p).revents |= POLLERR;

    return false;
}

bool TcpServer::Start()
{
    int result = listen(this->m_sckListen, INFINITE);
    if (result == SOCKET_ERROR)
    {
        throw std::runtime_error("Server listen failed with error: " + std::to_string(WSAGetLastError()));
    }

    WSAPOLLFD pfd{};
    pfd.fd = this->m_sckListen;
    pfd.events = POLLRDNORM;
    this->m_rvClients.push_back(pfd);

    this->m_isRunning = true;
    return true;
}

void TcpServer::ShutdownClient(const SOCKET &sckFd, bool hard)
{
    auto p = std::find_if(this->m_rvClients.begin(), this->m_rvClients.end(), [sckFd](WSAPOLLFD pfd) -> bool
                          { return pfd.fd == sckFd; });

    if (p == this->m_rvClients.end())
    {
        if (this->m_verbose) std::cout << "Cannot shutdown socket #" << sckFd << ": Client socket does not exist" << std::endl;
        return;
    }

    if ((*p).revents & POLLNVAL)
    {
        if (this->m_verbose) std::cout << "Cannot shutdown an invalid socket #" << sckFd << std::endl;
        return;
    }

    if (hard)
    {
        closesocket(sckFd);

        (*p).revents |= POLLNVAL;
        (*p).fd = INVALID_SOCKET;
        return;
    }

    size_t zero = 0;
    int sent = send(sckFd, (char*) &zero, sizeof zero, zero);
    int wle = WSAGetLastError();
    
    if (sent == 0)
        (*p).revents |= POLLHUP;
    if (sent != sizeof zero && wle != WSAEWOULDBLOCK)
        (*p).revents |= POLLERR;
}

bool TcpServer::Stop()
{
    closesocket(this->m_sckListen);
    this->m_rvClients.clear();
    this->m_sckListen = INVALID_SOCKET;
    this->m_isRunning = false;
    return true;
}
