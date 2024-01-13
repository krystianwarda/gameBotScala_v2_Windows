#include <WinSock2.h>
#include <vector>
#include <string>

#pragma comment(lib, "ws2_32.lib")

using std::vector, std::string;

class TcpServer
{
    private:

        SOCKET m_sckListen;
        vector<WSAPOLLFD> m_rvClients;
        bool m_isRunning;
        bool m_verbose;
        
        CRITICAL_SECTION m_mutex;

    public:

        TcpServer(const string &local_ip, unsigned short port, bool verbose = false);
        ~TcpServer();

        constexpr bool const IsRunning() const { return this->m_isRunning; }

        bool PollRequest(SOCKET& sckFd, sockaddr_in& clientAddr, std::string& requestStr);
        bool SendOn(const SOCKET& sckFd, const std::string& data);
        void ShutdownClient(const SOCKET& sckFd, bool hard = true);

        bool Start();
        bool Stop();
};