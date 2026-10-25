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
del BINARY.OK >NUL 2>NUL
del BINA.OUT >NUL 2>NUL
del BINB.OUT >NUL 2>NUL
del BINRAW.OUT >NUL 2>NUL
set TESTBAUD=%3
if "%TESTBAUD%"=="" set TESTBAUD=19200

rem DOSBox's transparent nullmodem carries bytes but not DCD.  Let LSppp
rem perform the real Hayes dial first; after CONNECT it exits because it
rem cannot observe carrier, leaving YAME in connected data mode.
echo Dialing YAME with LSppp...
LSPPP.EXE /d:1 /n:2 /B:%TESTBAUD% /V:60 /S:10.64.0.2 /D:5,1,0

rem Continue on the already-connected serial line in LSppp local mode.
rem This skips modem control only; LCP/IPCP are still negotiated normally.
echo Starting PPP on connected serial line...
LSPPP.EXE /l /n:2 /B:%TESTBAUD% /V:60 /S:10.64.0.2 /N:10.64.0.1
if errorlevel 1 goto ppp_failed
echo OK>PPP.OK

set MTCPCFG=C:\MTCP.CFG
if "%2"=="smoke" goto smoke
if "%2"=="binary" goto binary

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

:binary
rem Files are saved by the real DOS HTTP client, then verified on the host.
rem Connect to YAME's intercepted port 80 but fetch the local upstream on 18080.
rem This needs no privileged host listener, DNS fixture or system-wide sysctl.
set HTTP_PROXY=%1:80
echo Fetching small binary through HTTP compatibility...
HTGET.EXE -quiet -o BINA.OUT http://%1:18080/binary-small.bin
if errorlevel 22 goto binary_failed
if not errorlevel 21 goto binary_failed
echo Fetching large binary through HTTP compatibility...
HTGET.EXE -quiet -o BINB.OUT http://%1:18080/binary-large.bin
if errorlevel 22 goto binary_failed
if not errorlevel 21 goto binary_failed
echo Fetching large binary through the raw TCP proxy...
set HTTP_PROXY=
HTGET.EXE -quiet -o BINRAW.OUT http://%1:18080/binary-large.bin
if errorlevel 22 goto binary_failed
if not errorlevel 21 goto binary_failed
echo OK>BINARY.OK
goto done

:binary_failed
echo FAIL>BINARY.OK
goto done

:ppp_failed
echo FAIL>PPP.OK
if "%2"=="smoke" goto smoke_failed
if "%2"=="binary" goto binary_failed
echo FAIL>SUITE.OK
goto done

:dns_failed
echo FAIL>DNS.OK
if "%2"=="binary" goto binary_failed
echo FAIL>SUITE.OK
goto done

:tcp_failed
echo FAIL>TCP.OK
if "%2"=="binary" goto binary_failed
echo FAIL>SUITE.OK
goto done

:http_failed
echo FAIL>HTTP.OK
if "%2"=="binary" goto binary_failed
echo FAIL>SUITE.OK

:done
exit
