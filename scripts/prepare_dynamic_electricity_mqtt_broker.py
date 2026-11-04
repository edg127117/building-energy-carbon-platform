"""Start an explicitly isolated local test Broker from an already installed image.

Requires the dynamic-carbon clone ownership manifest. Creates dedicated loopback
port, Docker network, data directory, credentials and topics. Never connects to
or alters a field/shared Broker. Credentials remain under the selected root.
"""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import socket
import subprocess
import time


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--port',type=int,default=18897)
    parser.add_argument('--image',default='eclipse-mosquitto:2')
    parser.add_argument('--execute',action='store_true',required=True)
    args=parser.parse_args();root=args.root.resolve()
    config=json.loads((root/'private/local.json').read_text(encoding='utf-8'))
    if root.drive.upper()!='D:' or config['purpose']!='DYNAMIC_CARBON_EA84_ONLY' or Path(config['runtimeRoot']).resolve()!=root:
        raise ValueError('INDEPENDENT_INSTANCE_REQUIRED')
    if not 1024<=args.port<=65535 or args.port in config['ports'].values():raise ValueError('DEDICATED_BROKER_PORT_REQUIRED')
    def docker(*arguments,check=True):
        result=subprocess.run(['docker',*arguments],capture_output=True,text=True,timeout=60)
        if check and result.returncode:raise RuntimeError('ISOLATED_DOCKER_OPERATION_FAILED:'+arguments[0])
        return result
    identity=hashlib.sha256(str(root).encode()).hexdigest()[:10]
    name='dynamic-carbon-mqtt-'+identity;network=name+'-net';purpose='DYNAMIC_MQTT_EA84_ONLY'
    def configure_platform(broker):
        # Only this clone receives the new connection. Existing production defaults remain in repository configuration.
        backend=root/'private/backend.yml'
        settings={k:json.loads(v) for line in backend.read_text(encoding='utf-8-sig').splitlines() if line.strip() for k,v in [line.split(': ',1)]}
        settings.update({'mqtt.enabled':True,'mqtt.broker-url':'tcp://127.0.0.1:'+str(args.port),'mqtt.client-id':name+'-platform',
            'mqtt.username':broker['platformUser'],'mqtt.password':broker['platformPassword'],
            'mqtt.topics.upstream':broker['legacyTopic'],'mqtt.topics.standard-upstream':broker['standardTopic'],'mqtt.topics.v2-upstream':broker['v2Topic'],
            'mqtt.topics.v2-adapter-ack':broker['ackPrefix']+'/adapter','mqtt.tls.enabled':False,'mqtt.tls.allow-plaintext-for-tests':True})
        backend.write_text(''.join(k+': '+json.dumps(v,ensure_ascii=False)+'\n' for k,v in settings.items()),encoding='utf-8')
    private=root/'private/mqtt.json'
    if private.exists():
        saved=json.loads(private.read_text(encoding='utf-8'))
        if saved['purpose']!=purpose or saved['containerName']!=name:raise ValueError('BROKER_OWNERSHIP_MISMATCH')
        inspected=json.loads(docker('inspect',name).stdout)[0]
        if inspected['Config']['Labels'].get('codex.purpose')!=purpose:raise ValueError('BROKER_LABEL_MISMATCH')
        if inspected['Id']!=saved['containerId'] or inspected['Image']!=saved['imageId']:raise ValueError('BROKER_IDENTITY_MISMATCH')
        if saved['host']!='127.0.0.1' or set(inspected['NetworkSettings']['Networks'])!={saved['network']}:raise ValueError('DEDICATED_BROKER_NETWORK_REQUIRED')
        if not inspected['State']['Running']:docker('start',name)
        if saved['port']!=args.port:raise ValueError('BROKER_PORT_MISMATCH')
        ports=inspected['HostConfig']['PortBindings'].get('1883/tcp',[])
        if ports!=[{'HostIp':'127.0.0.1','HostPort':str(saved['port'])}]:raise ValueError('LOOPBACK_PORT_PUBLICATION_REQUIRED')
        configure_platform(saved)
        print('OWN_INDEPENDENT_BROKER_REUSED');return
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1',args.port))==0:raise ValueError('BROKER_PORT_OCCUPIED')
    if docker('inspect',name,check=False).returncode==0:raise ValueError('UNREGISTERED_CONTAINER_EXISTS')
    image=json.loads(docker('image','inspect',args.image).stdout)[0]
    directory=root/'mqtt';directory.mkdir(exist_ok=True)
    conf=directory/'config';data=directory/'data';conf.mkdir(exist_ok=True);data.mkdir(exist_ok=True)
    broker={'purpose':purpose,'containerName':name,'network':network,'host':'127.0.0.1','port':args.port,'imageId':image['Id'],
            'platformUser':'platform','platformPassword':secrets.token_urlsafe(28),
            'simulatorUser':'simulator','simulatorPassword':secrets.token_urlsafe(28),
            'standardTopic':name+'/standard/up','legacyTopic':name+'/legacy/up','v2Topic':name+'/v2/up','ackPrefix':name+'/ack'}
    passwords=conf/'passwords'
    passwords.write_text('platform:'+broker['platformPassword']+'\nsimulator:'+broker['simulatorPassword']+'\n',encoding='utf-8')
    docker('run','--rm','--network','none','-v',str(conf)+':/work',image['Id'],'mosquitto_passwd','-H','sha512-pbkdf2','-U','/work/passwords')
    (conf/'acl').write_text('user platform\n'+''.join('topic read '+broker[key]+'\n' for key in ['standardTopic','legacyTopic','v2Topic'])+
                            'topic write '+broker['ackPrefix']+'/#\nuser simulator\ntopic write '+broker['standardTopic']+'\n',encoding='utf-8')
    (conf/'mosquitto.conf').write_text('listener 1883\nallow_anonymous false\npassword_file /mosquitto/config/passwords\nacl_file /mosquitto/config/acl\n'
                'persistence true\npersistence_location /mosquitto/data/\nautosave_interval 1\nmax_queued_messages 20000\nlog_dest stdout\n',encoding='utf-8')
    if docker('network','inspect',network,check=False).returncode==0:raise ValueError('UNREGISTERED_NETWORK_EXISTS')
    docker('network','create','--label','codex.purpose='+purpose,network)
    container=docker('run','-d','--name',name,'--label','codex.purpose='+purpose,'--network',network,'--memory','256m',
                     '-p','127.0.0.1:'+str(args.port)+':1883','-v',str(conf)+':/mosquitto/config:ro','-v',str(data)+':/mosquitto/data',image['Id']).stdout.strip()
    broker['containerId']=container
    private.write_text(json.dumps(broker),encoding='utf-8')
    for _ in range(30):
        with socket.socket() as probe:
            probe.settimeout(.5)
            if probe.connect_ex(('127.0.0.1',args.port))==0:break
        time.sleep(1)
    else:raise RuntimeError('INDEPENDENT_BROKER_START_FAILED')
    configure_platform(broker)
    print(json.dumps({'result':'INDEPENDENT_MQTT_BROKER_READY','containerId':container,'port':args.port,'imageId':image['Id']}))


if __name__=='__main__':main()
