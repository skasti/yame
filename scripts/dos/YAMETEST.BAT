@echo off
del PPP.OK >NUL 2>NUL
del DNS.OK >NUL 2>NUL
del TCP.OK >NUL 2>NUL
del SUITE.OK >NUL 2>NUL
del DNS.OUT >NUL 2>NUL
del TCP.OUT >NUL 2>NUL

rem DOSBox's transparent nullmodem carries bytes but not DCD.  Let LSppp
rem perform the real Hayes dial first; after CONNECT it exits because it
rem cannot observe carrier, leaving YAME in connected data mode.
echo Dialing YAME with LSppp...
LSPPP.EXE /d:1 /n:2 /B:19200 /V:60 /S:10.64.0.2 /D:5,1,0

rem Continue on the already-connected serial line in LSppp local mode.
rem This skips modem control only; LCP/IPCP are still negotiated normally.
echo Starting PPP on connected serial line...
LSPPP.EXE /l /n:2 /B:19200 /V:60 /S:10.64.0.2 /N:10.64.0.1
if errorlevel 1 goto ppp_failed
echo OK>PPP.OK

set MTCPCFG=C:\MTCP.CFG
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

echo OK>SUITE.OK
goto done

:ppp_failed
echo FAIL>PPP.OK
echo FAIL>SUITE.OK
goto done

:dns_failed
echo FAIL>DNS.OK
echo FAIL>SUITE.OK
goto done

:tcp_failed
echo FAIL>TCP.OK
echo FAIL>SUITE.OK

:done
exit
