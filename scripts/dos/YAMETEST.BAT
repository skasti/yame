@echo off
del PPP.OK >NUL 2>NUL
del DNS.OK >NUL 2>NUL
del TCP.OK >NUL 2>NUL
del HTTP.OK >NUL 2>NUL
del SUITE.OK >NUL 2>NUL
del DNS.OUT >NUL 2>NUL
del TCP.OUT >NUL 2>NUL
del HTTP.OUT >NUL 2>NUL
del SMOKE.OK >NUL 2>NUL
del SMOKE.OUT >NUL 2>NUL

rem DOSBox's transparent nullmodem does not carry modem DCD status.
rem Dial YAME directly through the DOS COM2 device rather than using LSppp
rem dial mode, which sends +++ and ATH on its carrier-detection timeout.
rem Hayes escape/hangup now correctly closes the modem call.
echo Dialing YAME over COM2...
echo ATDT1>COM2

rem Start LSppp in local mode once YAME has entered data mode.
rem This skips carrier detection; LCP/IPCP are still negotiated normally.
echo Starting PPP on connected serial line...
LSPPP.EXE /l /n:2 /B:19200 /V:60 /S:10.64.0.2 /N:10.64.0.1
if errorlevel 1 goto ppp_failed
echo OK>PPP.OK

set MTCPCFG=C:\MTCP.CFG
if "%2"=="smoke" goto smoke

echo Resolving ci.yame.test through YAME...
DNSTEST.EXE -name ci.yame.test -timeout 10 > DNS.OUT
if errorlevel 1 goto dns_failed
echo OK>DNS.OK

echo Fetching tcp.yame.test through YAME TCP proxy...
HTGET.EXE -quiet -o TCP.OUT http://tcp.yame.test:18080/test.txt
rem mTCP HTGet maps HTTP 200 OK to DOS return code 21.
if errorlevel 22 goto tcp_failed
if not errorlevel 21 goto tcp_failed
echo OK>TCP.OK

rem The HTTP compatibility proxy (port 80) uses a local, deterministic fixture.
echo Fetching HTTP compatibility fixture through YAME...
HTGET.EXE -quiet -o HTTP.OUT http://%1/compat.txt
if errorlevel 22 goto http_failed
if not errorlevel 21 goto http_failed
if not exist HTTP.OUT goto http_failed
echo OK>HTTP.OK

echo OK>SUITE.OK
goto done

:smoke
rem Optional external smoke probe; the required suite never depends on this host.
echo Fetching Microsoft connectivity probe through YAME...
HTGET.EXE -quiet -o SMOKE.OUT http://www.msftconnecttest.com/connecttest.txt
rem mTCP HTGet returns DOS code 21 for HTTP 200.
if errorlevel 22 goto smoke_failed
if not errorlevel 21 goto smoke_failed
if not exist SMOKE.OUT goto smoke_failed
echo OK>SMOKE.OK
goto done

:smoke_failed
echo FAIL>SMOKE.OK
goto done

:ppp_failed
echo FAIL>PPP.OK
if "%2"=="smoke" goto smoke_failed
echo FAIL>SUITE.OK
goto done

:dns_failed
echo FAIL>DNS.OK
echo FAIL>SUITE.OK
goto done

:tcp_failed
echo FAIL>TCP.OK
echo FAIL>SUITE.OK
goto done

:http_failed
echo FAIL>HTTP.OK
echo FAIL>SUITE.OK

:done
exit
