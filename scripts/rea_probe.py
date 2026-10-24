#!/usr/bin/env python3
"""Probe an installed REA MCP server over stdio; retain responses without target secrets."""
import argparse
import json
import pathlib
import queue
import subprocess
import threading

p = argparse.ArgumentParser()
p.add_argument('--node', required=True)
p.add_argument('--launcher', required=True)
p.add_argument('--output', default='docs/evidence/rea-mcp.json')
p.add_argument('--apk')
p.add_argument('--serial')
a = p.parse_args()
proc = subprocess.Popen([a.node,a.launcher,'mcp'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
responses = queue.Queue()
def read():
    for line in proc.stdout:
        try: responses.put(json.loads(line))
        except json.JSONDecodeError: pass
threading.Thread(target=read,daemon=True).start()
def call(ident, method, params):
    proc.stdin.write(json.dumps({'jsonrpc':'2.0','id':ident,'method':method,'params':params})+'\n')
    proc.stdin.flush()
    while True:
        data = responses.get(timeout=45)
        if data.get('id') == ident: return data
try:
    init = call(1,'initialize',{'protocolVersion':'2024-11-05','capabilities':{},'clientInfo':{'name':'pairdroid-investigation','version':'0.1'}})
    proc.stdin.write(json.dumps({'jsonrpc':'2.0','method':'notifications/initialized'})+'\n'); proc.stdin.flush()
    catalog = call(2,'tools/list',{})
    tools = catalog.get('result',{}).get('tools',[])
    selected = ('inspect_android_package','inspect_adb_device')
    result = {'initialize':init,'tool_count':len(tools),'tool_names':[t['name'] for t in tools],
              'android_schemas':[t for t in tools if t['name'] in selected]}
    if a.apk:
        result['apk'] = call(3,'tools/call',{'name':'inspect_android_package','arguments':{'path':str(pathlib.Path(a.apk).resolve()),'detail':'summary'}})
    if a.serial:
        result['device'] = call(4,'tools/call',{'name':'inspect_adb_device','arguments':{'serial':a.serial}})
    # Report which real Android tools the server offers; no proprietary APK is supplied.
    pathlib.Path(a.output).write_text(json.dumps(result,indent=2))
    print(json.dumps({'server':init.get('result',{}).get('serverInfo'), 'tool_count':len(tools),'android_tools':[t['name'] for t in tools if any(w in (t['name']+' '+t.get('description','')).lower() for w in ('android','jadx','apk'))]}))
finally:
    proc.terminate()
    try: proc.wait(timeout=5)
    except subprocess.TimeoutExpired: proc.kill()
