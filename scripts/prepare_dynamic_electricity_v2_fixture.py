"""Prepare only the owned dynamic-carbon clone for real adapter/Broker simulation.

All endpoints, topics, users and protocol rows are local test fixtures. This does
not start a process, connect a device, or modify a shared/field configuration.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--execute',action='store_true',required=True)
    parser.add_argument('--platform-versions',action='store_true',help='Use the existing device timestamp as interval end; omit device revision/start fields.')
    args=parser.parse_args();root=args.root.resolve()
    read=lambda path:json.loads(path.read_text(encoding='utf-8-sig'))
    config=read(root/'private/local.json');broker=read(root/'private/mqtt.json');fixture=read(root/'live-fixture/manifest.json')
    if root.drive.upper()!='D:' or config['purpose']!='DYNAMIC_CARBON_EA84_ONLY' or Path(config['runtimeRoot']).resolve()!=root:
        raise ValueError('OWN_INDEPENDENT_INSTANCE_REQUIRED')
    prefix=broker['containerName']
    if broker['purpose']!='DYNAMIC_MQTT_EA84_ONLY' or broker['host']!='127.0.0.1' or not broker.get('v2AdaptersReady'):
        raise ValueError('OWN_V2_BROKER_REQUIRED')
    if any(not broker[key].startswith(prefix+'/') for key in ['rawTopic','v2Topic','ackPrefix']):raise ValueError('OWN_TOPICS_REQUIRED')
    if fixture['purpose']!='DYNAMIC_100_SIMULATED_ONLY' or len(fixture['points'])!=100 or fixture['buildingId']==config['buildingId']:
        raise ValueError('SEPARATE_100_METER_FIXTURE_REQUIRED')
    backend={k:json.loads(v) for line in (root/'private/backend.yml').read_text(encoding='utf-8-sig').splitlines() if line.strip() for k,v in [line.split(': ',1)]}
    url='tcp://127.0.0.1:'+str(broker['port'])
    if backend.get('mqtt.broker-url')!=url or backend.get('mqtt.topics.v2-upstream')!=broker['v2Topic']:raise ValueError('API_BROKER_SCOPE_MISMATCH')
    def sql(text,database='iot_platform'):
        env={k:v for k,v in os.environ.items() if k.lower() in {'systemroot','windir','path','temp','tmp','userprofile','localappdata','appdata'}}
        env['MYSQL_PWD']=config['mysqlPassword']
        result=subprocess.run([config['executables']['mysqlClient'],'--no-defaults','--protocol=TCP','-h127.0.0.1','-P'+str(config['ports']['mysql']),'-uroot','--default-character-set=utf8mb4','--batch','--skip-column-names',database],
                              input=text.encode('utf-8'),env=env,capture_output=True,timeout=60)
        if result.returncode:raise RuntimeError('OWN_V2_FIXTURE_SQL_FAILED')
        return result.stdout.decode('utf-8').strip()
    def quote(value):return "'"+str(value).replace('\\','\\\\').replace("'","''")+"'"
    building=fixture['buildingId']
    if sql('SELECT building_code FROM building WHERE building_id='+quote(building))!='CXG_DYNAMIC_100_TEST':raise ValueError('DATABASE_FIXTURE_IDENTITY_MISMATCH')
    database='iot_adapter_dynamic_v2';private=root/'private/v2-adapters.json'
    if private.exists():
        saved=read(private)
        if saved['purpose']!='DYNAMIC_V2_ADAPTERS_ONLY' or saved['database']!=database:raise ValueError('ADAPTER_OWNERSHIP_MISMATCH')
    else:
        if sql("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME="+quote(database)):raise ValueError('UNREGISTERED_ADAPTER_DATABASE')
        saved={'purpose':'DYNAMIC_V2_ADAPTERS_ONLY','database':database,'mysqlUser':'adapter_v2_'+hashlib.sha256(str(root).encode()).hexdigest()[:10],
               'mysqlPassword':secrets.token_urlsafe(28),'adapterPort':18198,'modbusHealthPort':18199,'modbusPort':18997}
        private.write_text(json.dumps(saved),encoding='utf-8')
    # Instantiate the adapter's existing schema in its own test database, never in the business schema.
    schema=(Path(__file__).resolve().parents[1]/'telemetry-adapter/src/main/resources/db/adapter-schema.sql').read_text(encoding='utf-8-sig')
    schema=schema.replace('`iot_adapter`','`'+database+'`')
    sql(schema)
    sql('CREATE USER IF NOT EXISTS '+quote(saved['mysqlUser'])+"@'127.0.0.1' IDENTIFIED BY "+quote(saved['mysqlPassword'])+'; GRANT SELECT ON '+database+'.* TO '+quote(saved['mysqlUser'])+"@'127.0.0.1';")
    profile='DYNAMIC_V2_100'
    sql("INSERT INTO iot_protocol_profile(profile_id,profile_code,profile_version,source_topic,device_identity_type,device_identity_path,protocol_version_path,expected_protocol_version,timestamp_path,seq_path,message_id_path,boot_id_path,max_ack_mode,correlation_policy,enabled) VALUES ("+
        ','.join(quote(v) for v in [profile,'DYNAMIC_INTERVAL_TEST',1,broker['rawTopic'],'SERIAL_NO','/meter','/protocol','DYNAMIC_PERIOD_CO2','/time','/seq','/messageId','/boot','ADAPTER_PROXY','BOOT_ID_AND_SEQ',1])+
        ') ON DUPLICATE KEY UPDATE source_topic=VALUES(source_topic);',database)
    fields=[('energy','Q','ELECTRICITY_KWH','kWh',0),('factor','F','CO2_FACTOR','kgCO2/kWh',0),('from','FROM','INTERVAL_START','ms',1),('to','TO','INTERVAL_END','ms',1),('revision','REV','INTERVAL_REVISION','1',1)]
    for index,(field,code,kind,unit,required) in enumerate(fields):
        sql('INSERT INTO iot_protocol_field_mapping(mapping_id,profile_id,source_path,metric_code,value_type,source_unit,target_unit,required_flag,enabled,sort_order) VALUES ('+
            ','.join(quote(v) for v in ['DYN_V2_'+str(index),profile,'/'+field,code,kind,unit,unit,required,1,index])+
            ') ON DUPLICATE KEY UPDATE value_type=VALUES(value_type);',database)
    if args.platform_versions:
        sql("UPDATE iot_protocol_field_mapping SET enabled=0 WHERE profile_id="+quote(profile)+" AND value_type IN ('INTERVAL_START','INTERVAL_REVISION');"+
            "UPDATE iot_protocol_field_mapping SET source_path='/time' WHERE profile_id="+quote(profile)+" AND value_type='INTERVAL_END';",database)
    else:
        sql("UPDATE iot_protocol_field_mapping SET enabled=1 WHERE profile_id="+quote(profile)+"; UPDATE iot_protocol_field_mapping SET source_path='/to' WHERE profile_id="+quote(profile)+" AND value_type='INTERVAL_END';",database)
    source=hashlib.sha256((building+'V2_SIMULATED_ONLY').encode()).hexdigest()[:32]
    statements=["INSERT INTO biz_data_source(source_id,source_code,source_name,building_id,source_category,transport_type,status,config_revision,runtime_revision) VALUES ("+
                ','.join(quote(v) for v in [source,'DYNAMIC_V2_TEST','SIMULATED_V2_ONLY',building,'DEVICE_ACCESS','MQTT','ENABLED',1,1])+') ON DUPLICATE KEY UPDATE source_id=source_id;']
    for meter,point in enumerate(fixture['points']):
        identity='DYNAMIC_100_'+str(meter);alias=hashlib.sha256((building+identity+'V2Q').encode()).hexdigest()[:32]
        statements.append('INSERT INTO biz_point_alias(alias_id,building_id,source_id,source_system,source_point_code,point_id,status,revision) VALUES ('+
                          ','.join(quote(v) for v in [alias,building,source,'MQTT_STANDARD_V1','SERIAL_NO:'+identity+':Q',point['pointId'],1,1])+') ON DUPLICATE KEY UPDATE alias_id=alias_id;')
        statements.append("UPDATE biz_device_identity SET max_ack_mode='ADAPTER_PROXY',correlation_policy='BOOT_ID_AND_SEQ',adapter_ack_topic="+quote(broker['ackPrefix']+'/adapter')+
                          ' WHERE building_id='+quote(building)+" AND identity_type='SERIAL_NO' AND identity_value="+quote(identity)+';')
    sql('\n'.join(statements))
    adapter={'server.address':'127.0.0.1','server.port':saved['adapterPort'],'spring.datasource.driver-class-name':'com.mysql.cj.jdbc.Driver',
             'spring.datasource.url':'jdbc:mysql://127.0.0.1:'+str(config['ports']['mysql'])+'/'+database+'?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true',
             'spring.datasource.username':saved['mysqlUser'],'spring.datasource.password':saved['mysqlPassword'],'adapter.profile.mode':'jdbc','adapter.profile.output-version':'V2',
             'adapter.mqtt.enabled':True,'adapter.mqtt.broker-url':url,'adapter.mqtt.client-id':prefix+'-adapter','adapter.mqtt.username':broker['adapterUser'],'adapter.mqtt.password':broker['adapterPassword'],
             'adapter.mqtt.raw-topic':broker['rawTopic'],'adapter.mqtt.standard-topic':broker['v2Topic'],'adapter.mqtt.application-ack-topic':broker['ackPrefix']+'/adapter',
             'adapter.mqtt.tls.enabled':False,'adapter.mqtt.tls.allow-plaintext-for-tests':True,'management.endpoints.web.exposure.include':'health,metrics'}
    def yaml_file(path,settings):path.write_text(''.join(k+': '+json.dumps(v,ensure_ascii=False)+'\n' for k,v in settings.items()),encoding='utf-8')
    yaml_file(root/'private/telemetry-v2.yml',adapter)
    edge={'server.address':'127.0.0.1','server.port':saved['modbusHealthPort'],'modbus-edge.enabled':True,'modbus-edge.worker-threads':1,
          'modbus-edge.mqtt.enabled':True,'modbus-edge.mqtt.broker-url':url,'modbus-edge.mqtt.client-id':prefix+'-modbus','modbus-edge.mqtt.username':broker['edgeUser'],'modbus-edge.mqtt.password':broker['edgePassword'],
          'modbus-edge.mqtt.standard-topic':broker['v2Topic'],'modbus-edge.mqtt.application-ack-topic':broker['ackPrefix']+'/adapter','modbus-edge.mqtt.tls.enabled':False,'modbus-edge.mqtt.tls.allow-plaintext-for-tests':True,
          'modbus-edge.devices[0].name':'SIMULATED_ONLY','modbus-edge.devices[0].identity-type':'SERIAL_NO','modbus-edge.devices[0].identity-value':'DYNAMIC_100_0','modbus-edge.devices[0].profile-code':'DYNAMIC_INTERVAL_TEST',
          'modbus-edge.devices[0].poll-interval':'15s','modbus-edge.devices[0].connection.type':'TCP','modbus-edge.devices[0].connection.host':'127.0.0.1','modbus-edge.devices[0].connection.port':saved['modbusPort'],'modbus-edge.devices[0].connection.unit-id':1}
    for index,(_,code,kind,unit,_) in enumerate(fields):
        if args.platform_versions and kind in ('INTERVAL_START','INTERVAL_REVISION'):continue
        if args.platform_versions:index={'ELECTRICITY_KWH':0,'CO2_FACTOR':1,'INTERVAL_END':2}[kind]
        key='modbus-edge.devices[0].points['+str(index)+'].'
        edge.update({key+'code':code,key+'value-type':kind,key+'unit':unit,key+'address':(3 if args.platform_versions and kind=='INTERVAL_END' else index)*4,key+'function':'HOLDING_REGISTER',key+'data-type':'FLOAT64'})
    yaml_file(root/'private/modbus-v2.yml',edge)
    print('OWN_V2_ADAPTER_FIXTURE_PREPARED_NO_PROCESS_STARTED')


if __name__=='__main__':main()
