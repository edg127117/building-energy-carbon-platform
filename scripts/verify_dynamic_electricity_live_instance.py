"""Explicit isolated MySQL/HTTP acceptance for 100 simulated building meters.

Uses the separate live-fixture prepared by prepare_dynamic_electricity_fixture.py.
Device identity rows are synthetic fixture provisioning only. Uploads always use
the authenticated public interval API, never insert calculated business results.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timedelta,timezone
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import urllib.error


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--revision',type=int,default=1)
    parser.add_argument('--execute',action='store_true',required=True)
    parser.add_argument('--stage',choices=['identity','history','upload','verify','pending-restart','resume-restart'],required=True)
    args=parser.parse_args()
    root=args.root.resolve()
    config=json.loads((root/'private/local.json').read_text(encoding='utf-8'))
    if root.drive.upper()!='D:' or config['purpose']!='DYNAMIC_CARBON_EA84_ONLY' or Path(config['runtimeRoot']).resolve()!=root:
        raise ValueError('ISOLATED_INSTANCE_REQUIRED')
    fixture=json.loads((root/'live-fixture/manifest.json').read_text(encoding='utf-8'))
    if fixture['purpose']!='DYNAMIC_100_SIMULATED_ONLY' or fixture['meterCount']!=100 or fixture['buildingId']==config['buildingId']:
        raise ValueError('SEPARATE_100_METER_FIXTURE_REQUIRED')
    building=fixture['buildingId'];points=fixture['points']
    output=root/'live-acceptance';output.mkdir(exist_ok=True)
    base=f"http://127.0.0.1:{config['ports']['api']}/api"
    tokens={}
    def request(path,body=None,role='admin',method=None):
        headers={'Content-Type':'application/json'}
        if role in tokens:headers['Authorization']='Bearer '+tokens[role]
        payload=json.dumps(body,ensure_ascii=False).encode() if body is not None else None
        try:
            with urllib.request.urlopen(urllib.request.Request(base+path,data=payload,headers=headers,method=method),timeout=180) as response:
                data=json.load(response)
        except urllib.error.HTTPError as e:
            data=json.loads(e.read());raise RuntimeError(f"HTTP_{e.code}:{data.get('errorCode')}:{data.get('msg')}") from None
        if data.get('success') is False:raise RuntimeError('API_REJECTED:'+str(data.get('errorCode')))
        return data.get('data',data)
    if args.stage=='identity':
        statements=[]
        for index,point in enumerate(points):
            equipment=json.loads((root/f'live-fixture/equipment-{index}.json').read_text(encoding='utf-8'))
            equipment_id=equipment.get('equipId') or equipment['equipmentId']
            identity='DYNAMIC_100_'+str(index)
            row_id=hashlib.sha256((building+identity).encode()).hexdigest()[:32]
            statements.append("INSERT INTO biz_device_identity(identity_id,identity_type,identity_value,equip_id,building_id,expected_profile_code,status) VALUES ('%s','SERIAL_NO','%s','%s','%s','DYNAMIC_INTERVAL_TEST',1) ON DUPLICATE KEY UPDATE identity_id=identity_id;"%(row_id,identity,equipment_id,building))
        command=[config['executables']['mysqlClient'],'--no-defaults','--protocol=TCP','-h127.0.0.1','-P'+str(config['ports']['mysql']),'-uroot','--default-character-set=utf8mb4','iot_platform']
        result=subprocess.run(command,input='\n'.join(statements).encode(),env={**os.environ,'MYSQL_PWD':config['mysqlPassword']},capture_output=True,timeout=30)
        if result.returncode:raise RuntimeError('ISOLATED_IDENTITY_FIXTURE_FAILED')
        print('ISOLATED_100_IDENTITIES_PREPARED');return
    for role in (['admin','reviewer'] if args.stage=='history' else ['admin']):tokens[role]=request('/auth/login',config['accounts'][role],role)['token']
    api=f'/v1/carbon-management/buildings/{building}/dynamic-electricity'
    zone=timezone(timedelta(hours=8))
    first=datetime(2026,10,1,tzinfo=zone)
    def iso(dt):return dt.astimezone(timezone.utc).isoformat()
    def view():return request(api+'?periodType=MONTH&period=2026-10')
    def annual():return request(api+'?periodType=YEAR&period=2026')
    def save(name,value):
        (output/(name+'.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    if args.stage=='history':
        # Other days supply a complete monthly baseline, while the tested day is filled only by device uploads.
        observations=[{'pointId':'*','startInclusive':iso(first+timedelta(days=1)),'endExclusive':iso(first+timedelta(days=31)),'factorKgCo2PerKwh':'0.5'}]
        request(api+'/factors',{'idempotencyKey':'live-history-factor','sourceReference':'SIMULATED_100_HISTORY','sourceRegion':'TEST','scenario':'TEST','sourceNature':'SIMULATED','unit':'kgCO2/kWh','observations':observations})
        readings=[]
        for day in range(1,31):
            for index,point in enumerate(points):
                readings.append({'pointId':point['pointId'],'startInclusive':iso(first+timedelta(days=day)),'endExclusive':iso(first+timedelta(days=day+1)),
                    'energyItemCode':'ELECTRICITY','unit':'kWh','semantics':'PERIOD_TOTAL','quantity':'9504' if index==0 else '96',
                    'multiplier':'1','alreadyScaled':True,'status':'CONFIRMED','sourceReference':'SIMULATED_100_HISTORY',
                    'timePrecision':'EXACT','supersedesFactId':None})
        route=f'/v1/energy-classification/buildings/{building}/readings'
        for offset in range(0,len(readings),498):
            batch=request(route+'/import',{'idempotencyKey':'live-history-'+str(offset),'readings':readings[offset:offset+498],'reason':'SIMULATED_100_HISTORY'})
            current=request(route+'/batches/'+batch['batchId'])
            if current['status']!='APPROVED':request(route+'/batches/'+batch['batchId']+'/approve',{'expectedRevision':current['revision'],'reason':'SIMULATED_100_HISTORY'},'reviewer')
        request(api+'/calculations',{'grain':'DAY','startInclusive':iso(first+timedelta(days=1)),'endExclusive':iso(first+timedelta(days=31))})
        save('before-upload',view());print('ISOLATED_HISTORY_READY');return
    def upload(slot,index,revision=None,quantity=None,factor=True):
        if revision is None:revision=args.revision
        start=first+timedelta(minutes=15*slot)
        body={'deviceIdentity':{'type':'SERIAL_NO','value':'DYNAMIC_100_'+str(index)},'profileCode':'DYNAMIC_INTERVAL_TEST','pointId':points[index]['pointId'],
            'startInclusive':iso(start),'endExclusive':iso(start+timedelta(minutes=15)),'revision':revision,
            'electricityKwh':quantity if quantity is not None else ('99' if index==0 else '1'),
            'factorKgCo2PerKwh':('0.4' if index%2==0 else '0.6') if factor else None,'sourceNature':'SIMULATED','sourceReference':'SIMULATED_100_UPLOAD'}
        return request(api+'/intervals',body)
    def wait_for_day(expected,timeout=40):
        until=time.monotonic()+timeout
        while time.monotonic()<until:
            response=view();point=response['points'][0]
            if point['status']=='COMPLETE' and Decimal(point['emissionTco2'])==expected:return response
            time.sleep(.5)
        raise RuntimeError('AUTO_COMPUTE_TIMEOUT_OR_WRONG_VALUE')
    expected=Decimal('4.7616')
    if args.stage=='upload':
        measurements=[]
        with ThreadPoolExecutor(max_workers=10) as pool:
            for slot in range(96):
                began=time.monotonic()
                list(pool.map(lambda i:upload(slot,i),range(100)))
                ended=time.monotonic()
                measurements.append({'slot':slot,'uploadSeconds':ended-began})
                if slot%12==0:print('BURST_READY',slot,flush=True)
        finished=time.monotonic();save('input-times',{'groups':9600,'revision':args.revision,'lastInputAtMs':int(time.time()*1000),'bursts':measurements})
        result=wait_for_day(expected,timeout=120)
        latency=time.monotonic()-finished
        if latency>30:raise RuntimeError('LAST_INPUT_TO_RESULT_OVER_30_SECONDS')
        save('upload',{'groups':9600,'bursts':measurements,'lastInputToResultSeconds':latency,'view':result,'year':annual()})
        # Same-version retries and separated correction inputs must not double count or publish a mixed revision.
        upload(0,1);before=annual()['points'][9]
        upload(0,1,args.revision+1,'2',False)
        time.sleep(1.5)
        partial=view()['points'][0]
        if partial['emissionTco2'] is not None:raise RuntimeError('MIXED_REVISION_WAS_PUBLISHED')
        upload(0,0,args.revision+1,'100',True)
        upload(0,1,args.revision+1,'2',True)
        corrected=wait_for_day(expected+Decimal('0.0006'))
        save('corrected',{'view':corrected,'year':annual(),'previousOctober':before})
        print('INDEPENDENT_100_METER_AUTO_CHAIN_OK');return
    if args.stage=='pending-restart':
        receipt=upload(0,1,args.revision,'2',False)
        until=time.monotonic()+10
        while time.monotonic()<until:
            pending=view()
            if pending['points'][0]['emissionTco2'] is None:break
            time.sleep(.5)
        else:raise RuntimeError('PENDING_INPUT_WAS_NOT_INVALIDATED')
        save('pending-restart',{'revision':args.revision,'receipt':receipt,'view':pending})
        print('PERSISTED_PENDING_INPUT_READY_FOR_RESTART');return
    if args.stage=='resume-restart':
        upload(0,1,args.revision,'2',True)
        result=wait_for_day(expected+Decimal('0.0006'))
        save('resumed',{'revision':args.revision,'view':result,'year':annual()})
        print('INDEPENDENT_RESTART_RECOVERY_OK');return
    current=view();year=annual();point=current['points'][0]
    if point['status']!='COMPLETE' or Decimal(point['emissionTco2'])!=expected+Decimal('0.0006'):raise RuntimeError('DAY_RESULT_MISMATCH')
    month=year['points'][9]
    if month['status']!='COMPLETE' or Decimal(month['electricityKwh'])!=Decimal('294625') or Decimal(month['emissionTco2'])!=Decimal('147.3222'):raise RuntimeError('MONTH_YEAR_CONSERVATION_FAILED')
    if abs(Decimal(month['electricityKwh'])*Decimal(month['factorKgCo2PerKwh'])-Decimal(month['emissionTco2'])*1000)>Decimal('0.000000001'):raise RuntimeError('WEIGHTED_FACTOR_MISMATCH')
    hours=request(api+'/hourly?date=2026-10-01')
    if len([p for p in hours['points'] if p['status']=='COMPLETE'])!=24:raise RuntimeError('HOURLY_CHAIN_FAILED')
    save('verified',{'view':current,'year':year,'hours':hours});print('INDEPENDENT_100_METER_CONSERVATION_OK')


if __name__=='__main__':main()
