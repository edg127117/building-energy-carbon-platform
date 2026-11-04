package com.platform.carbon;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 显式独立Broker/MySQL/HTTP验收；V2模式只发原始主题，必须经实际适配器，HTTP仅登录读取、SQL只读。 */
@EnabledIfEnvironmentVariable(named="DYNAMIC_MQTT_IT_ROOT",matches="D:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamicElectricityMqttIndependentAcceptanceTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final MqttClient[] clients=new MqttClient[100];
    private final long[] revisions=new long[100];
    private final Instant first=LocalDate.of(2026,10,1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    private Path root,out;
    private JsonNode config,broker,fixture;
    private String building,token,base,stage;
    private Connection database;
    private long revision;
    private boolean viaAdapter;
    private String transport;
    private MqttClient ackListener;
    private final Set<String> applicationAcks=ConcurrentHashMap.newKeySet();

    @BeforeAll void connect() throws Exception {
        root=Path.of(System.getenv("DYNAMIC_MQTT_IT_ROOT")).toRealPath();
        config=json.readTree(root.resolve("private/local.json").toFile());
        broker=json.readTree(root.resolve("private/mqtt.json").toFile());
        fixture=json.readTree(root.resolve("live-fixture/manifest.json").toFile());
        assertThat(root.toString()).startsWith("D:");
        assertThat(config.path("purpose").asText()).isEqualTo("DYNAMIC_CARBON_EA84_ONLY");
        assertThat(Path.of(config.path("runtimeRoot").asText()).toRealPath()).isEqualTo(root);
        assertThat(broker.path("purpose").asText()).isEqualTo("DYNAMIC_MQTT_EA84_ONLY");
        assertThat(broker.path("host").asText()).isEqualTo("127.0.0.1");
        assertThat(fixture.path("purpose").asText()).isEqualTo("DYNAMIC_100_SIMULATED_ONLY");
        assertThat(fixture.path("points").size()).isEqualTo(100);
        building=fixture.path("buildingId").asText();assertThat(building).isNotEqualTo(config.path("buildingId").asText());
        base="http://127.0.0.1:"+config.path("ports").path("api").asInt()+"/api";
        stage=System.getenv().getOrDefault("DYNAMIC_MQTT_IT_STAGE","full");
        String route=System.getenv().getOrDefault("DYNAMIC_MQTT_IT_TRANSPORT","STANDARD_V1");
        assertThat(route).isIn("STANDARD_V1","V2_ADAPTER");viaAdapter="V2_ADAPTER".equals(route);
        if(viaAdapter){assertThat(broker.path("v2AdaptersReady").asBoolean()).isTrue();assertThat(broker.path("rawTopic").asText()).startsWith(broker.path("containerName").asText()+"/");}
        transport=viaAdapter?"MQTT_STANDARD_V2":"MQTT_STANDARD_V1";
        revision=Long.parseLong(System.getenv().getOrDefault("DYNAMIC_MQTT_IT_REVISION","100"));
        assertThat(revision).isPositive();Arrays.fill(revisions,revision);
        out=root.resolve(viaAdapter?"mqtt-v2-acceptance":"mqtt-acceptance");Files.createDirectories(out);
        database=DriverManager.getConnection("jdbc:mysql://127.0.0.1:"+config.path("ports").path("mysql").asInt()+"/iot_platform?serverTimezone=Asia%2FShanghai&useSSL=false&allowPublicKeyRetrieval=true","root",config.path("mysqlPassword").asText());
        database.setReadOnly(true);
        if(!"offline-publish".equals(stage))token=api("/auth/login",config.path("accounts").path("admin")).path("token").asText();
        if(viaAdapter&&"full".equals(stage)) {
            ackListener=new MqttClient("tcp://127.0.0.1:"+broker.path("port").asInt(),broker.path("containerName").asText()+"-acceptance-ack",new MemoryPersistence());
            ackListener.setTimeToWait(10000);ackListener.setCallback(new MqttCallback() {
                public void connectionLost(Throwable failure) { }
                public void deliveryComplete(IMqttDeliveryToken token) { }
                public void messageArrived(String topic,MqttMessage message)throws Exception {
                    var ack=json.readTree(message.getPayload());
                    if(Set.of("PERIOD_INPUT_PERSISTED","DUPLICATE_PERIOD_INPUT").contains(ack.path("status").asText()))applicationAcks.add(ack.path("canonicalMessageId").asText());
                }
            });
            var options=new MqttConnectOptions();options.setUserName(broker.path("edgeUser").asText());options.setPassword(broker.path("edgePassword").asText().toCharArray());options.setCleanSession(true);options.setConnectionTimeout(10);
            ackListener.connect(options);ackListener.subscribe(broker.path("ackPrefix").asText()+"/adapter",1);
        }
    }
    @AfterAll void close() throws Exception {
        for(var client:clients)if(client!=null){if(client.isConnected())client.disconnect();client.close();}
        if(database!=null)database.close();
        if(ackListener!=null){if(ackListener.isConnected())ackListener.disconnect();ackListener.close();}
    }
    private String path(){return "/v1/carbon-management/buildings/"+building+"/dynamic-electricity";}
    private JsonNode api(String path,JsonNode body) throws Exception {
        if(body!=null&&!"/auth/login".equals(path))throw new IllegalArgumentException("HTTP_UPLOAD_FORBIDDEN_IN_MQTT_ACCEPTANCE");
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(90)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body==null)request.GET();else request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP status for %s",path).isEqualTo(200);
        var value=json.readTree(response.body());assertThat(value.path("success").asBoolean(true)).isTrue();return value.path("data");
    }
    private synchronized MqttClient client(int meter) throws Exception {
        if(clients[meter]!=null)return clients[meter];
        var client=new MqttClient("tcp://127.0.0.1:"+broker.path("port").asInt(),"dyn-ea84-sim-"+meter,new MemoryPersistence());
        client.setTimeToWait(10000);
        var options=new MqttConnectOptions();options.setUserName(broker.path("simulatorUser").asText());options.setPassword(broker.path("simulatorPassword").asText().toCharArray());
        options.setCleanSession(true);options.setConnectionTimeout(10);client.connect(options);clients[meter]=client;return client;
    }
    private ObjectNode payload(int slot,int meter,long version,String quantity,boolean factor) {
        Instant from=first.plusSeconds(slot*900L),to=from.plusSeconds(900);
        var body=json.createObjectNode();body.put("standardVersion","1.0");body.put("profileCode","DYNAMIC_INTERVAL_TEST");body.put("profileVersion",1);
        body.putObject("deviceIdentity").put("type","SERIAL_NO").put("value","DYNAMIC_100_"+meter);
        body.put("eventTime",to.toEpochMilli());body.put("receivedTime",System.currentTimeMillis());body.put("timeSource","DEVICE_REPORTED");body.put("seq",version*100+slot);body.putArray("metrics");
        var input=body.putObject("electricityInterval");input.put("pointId",fixture.path("points").get(meter).path("pointId").asText());
        input.put("startInclusive",from.toString());input.put("endExclusive",to.toString());input.put("revision",version);
        if(quantity!=null)input.put("electricityKwh",quantity);
        if(factor)input.put("factorKgCo2PerKwh",meter%2==0?"0.4":"0.6");
        input.put("electricityUnit","kWh");input.put("factorUnit","kgCO2/kWh");input.put("sourceNature","SIMULATED");input.put("sourceReference","MQTT_100_SIMULATED_INTERVALS");
        return body;
    }
    private void publish(int slot,int meter,long version,String quantity,boolean factor) throws Exception {publish(meter,payload(slot,meter,version,quantity,factor));}
    private void publish(int meter,ObjectNode body) throws Exception {
        var message=new MqttMessage(json.writeValueAsBytes(viaAdapter?vendor(body):body));message.setQos(1);client(meter).publish(broker.path(viaAdapter?"rawTopic":"standardTopic").asText(),message);
    }
    private ObjectNode vendor(ObjectNode standard) {
        var p=standard.path("electricityInterval");var raw=json.createObjectNode();String meter=standard.path("deviceIdentity").path("value").asText();
        raw.put("meter",meter);raw.put("protocol","kgCO2/kWh".equals(p.path("factorUnit").asText())?"DYNAMIC_PERIOD_CO2":"INVALID_UNIT_PROTOCOL");
        raw.put("time",standard.path("eventTime").asLong());raw.put("seq",standard.path("seq").asLong());raw.put("boot","V2_SIMULATED");
        raw.put("messageId",meter+":"+p.path("startInclusive").asText()+":"+p.path("revision").asLong());
        raw.put("from",Instant.parse(p.path("startInclusive").asText()).toEpochMilli());raw.put("to",Instant.parse(p.path("endExclusive").asText()).toEpochMilli());raw.put("revision",p.path("revision").asLong());
        if(p.hasNonNull("electricityKwh"))raw.put("energy",new BigDecimal(p.path("electricityKwh").asText()));
        if(p.hasNonNull("factorKgCo2PerKwh"))raw.put("factor",new BigDecimal(p.path("factorKgCo2PerKwh").asText()));
        // 厂家报文携带的内部归属声明不被适配器采纳，可信测点由服务器别名确定。
        raw.put("pointId",p.path("pointId").asText());raw.put("buildingId","UNTRUSTED_BUILDING");return raw;
    }
    private record Task(String state,long actor,long revision,String resultId,JsonNode inputs) { }
    private Task task(int slot) throws Exception {
        try(var statement=database.prepareStatement("SELECT state,requested_by,revision,result_id,input_json FROM biz_dynamic_electricity_task WHERE building_id=? AND start_ms=? AND end_ms=?")) {
            long from=first.plusSeconds(slot*900L).toEpochMilli();statement.setString(1,building);statement.setLong(2,from);statement.setLong(3,from+900000);
            try(var rows=statement.executeQuery()){if(!rows.next())return null;return new Task(rows.getString(1),rows.getLong(2),rows.getLong(3),rows.getString(4),json.readTree(rows.getString(5)));}
        }
    }
    private Task awaitReady(int slot,long[] expected) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(System.nanoTime()<until) {
            var task=task(slot);
            if(task!=null&&task.state().equals("READY")&&task.actor()==0&&task.inputs().size()==100) {
                boolean matches=true;
                for(int meter=0;meter<100;meter++) {
                    var source=task.inputs().path(fixture.path("points").get(meter).path("pointId").asText());
                    matches&=source.path("quantityRevision").asLong()==expected[meter]&&source.path("factorRevision").asLong()==expected[meter]
                            &&transport.equals(source.path("transport").asText())&&!source.path("identityId").asText().isBlank();
                }
                if(matches)return task;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("MQTT_PLATFORM_TASK_NOT_READY_WITHIN_30_SECONDS slot="+slot);
    }
    private void awaitSplit(int slot,int meter,long version) throws Exception {
        String point=fixture.path("points").get(meter).path("pointId").asText();long until=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(System.nanoTime()<until) {
            var task=task(slot);var source=task==null?json.createObjectNode():task.inputs().path(point);
            if(source.path("quantityRevision").asLong()==version&&source.path("factorRevision").asLong()<version&&task.actor()==0&&!"READY".equals(task.state()))return;
            Thread.sleep(100);
        }
        throw new AssertionError("SPLIT_INPUT_NOT_OBSERVED");
    }
    private void save(String file,Object data)throws Exception {json.writerWithDefaultPrettyPrinter().writeValue(out.resolve(file+".json").toFile(),data);}
    private void checkResults() throws Exception {
        var monthly=api(path()+"?periodType=MONTH&period=2026-10",null);var annual=api(path()+"?periodType=YEAR&period=2026",null);
        var day=monthly.path("points").get(0);var month=annual.path("points").get(9);
        assertThat(day.path("status").asText()).isEqualTo("COMPLETE");assertThat(new BigDecimal(day.path("electricityKwh").asText())).isEqualByComparingTo("9505");
        assertThat(new BigDecimal(day.path("emissionTco2").asText())).isEqualByComparingTo("4.7622");
        assertThat(month.path("status").asText()).isEqualTo("COMPLETE");assertThat(new BigDecimal(month.path("electricityKwh").asText())).isEqualByComparingTo("294625");
        assertThat(new BigDecimal(month.path("emissionTco2").asText())).isEqualByComparingTo("147.3222");
        assertThat(new BigDecimal(month.path("electricityKwh").asText()).multiply(new BigDecimal(month.path("factorKgCo2PerKwh").asText()))
                .subtract(new BigDecimal(month.path("emissionTco2").asText()).movePointRight(3)).abs()).isLessThan(new BigDecimal("0.000000001"));
        var hours=api(path()+"/hourly?date=2026-10-01",null);assertThat(hours.path("points")).hasSize(24);
        for(var point:hours.path("points"))assertThat(point.path("status").asText()).isEqualTo("COMPLETE");
        save("results",Map.of("monthly",monthly,"annual",annual,"hours",hours));
    }

    @Test void brokerToPlatformCalculationAndScreenData() throws Exception {
        if("pending-restart".equals(stage)) {
            publish(95,1,revision,"2",false);awaitSplit(95,1,revision);
            var view=api(path()+"?periodType=MONTH&period=2026-10",null);assertThat(view.path("points").get(0).path("emissionTco2").isNull()).isTrue();
            save("pending-restart",Map.of("revision",revision,"view",view));System.out.println("MQTT_PENDING_INPUT_PERSISTED");return;
        }
        if("offline-publish".equals(stage)) {
            var before=task(95);publish(95,1,revision,null,true);Thread.sleep(500);var after=task(95);
            assertThat(after.inputs()).isEqualTo(before.inputs());save("broker-offline-queue",Map.of("revision",revision,"platformInputUnchanged",true));
            System.out.println("MQTT_BROKER_ACCEPTED_PLATFORM_NOT_YET_PERSISTED");return;
        }
        if("resume-restart".equals(stage)) {
            var current=task(95);for(int meter=0;meter<100;meter++)revisions[meter]=current.inputs().path(fixture.path("points").get(meter).path("pointId").asText()).path("quantityRevision").asLong();
            revisions[1]=revision;awaitReady(95,revisions);checkResults();save("restart-recovered",Map.of("revision",revision,"readOnlyObservation",true));
            System.out.println("MQTT_OFFLINE_QUEUE_RESTART_RECOVERY_OK");return;
        }
        assertThat(stage).isEqualTo("full");
        for(int meter=0;meter<100;meter++)client(meter);
        var measured=new ArrayList<Map<String,Object>>();long started=System.nanoTime();
        try(var pool=Executors.newFixedThreadPool(10)) {
            for(int slot=0;slot<96;slot++) {
                int index=slot;long before=System.nanoTime();var work=new ArrayList<Callable<Void>>();
                for(int meter=0;meter<100;meter++){int id=meter;work.add(()->{publish(index,id,revision,id==0?"99":"1",true);return null;});}
                for(var future:pool.invokeAll(work))future.get();long afterBrokerAck=System.nanoTime();var ready=awaitReady(slot,revisions);
                measured.add(Map.of("slot",slot,"publish100Ms",(afterBrokerAck-before)/1000000,"afterBrokerAckToPlatformReadyMs",(System.nanoTime()-afterBrokerAck)/1000000));
                if(slot%12==0){save("progress",Map.of("completedIntervals",slot+1,"pairedUploads",(slot+1)*100));System.out.println("MQTT_INTERVALS_READY "+(slot+1));}
                assertThat(ready.actor()).isZero();
            }
        }
        save("load",Map.of("meters",100,"intervalMinutes",15,"intervals",96,"pairedUploads",9600,"clockMode","EVENT_TIME_COMPRESSED_WITH_EACH_BATCH_DRAINED",
                "elapsedMs",(System.nanoTime()-started)/1000000,"measurements",measured));
        if(viaAdapter)try(var statement=database.prepareStatement("SELECT COUNT(*) FROM biz_telemetry_receipt WHERE building_id=? AND receipt_status='PERIOD_INPUT_PERSISTED' AND metric_count=0")) {
            statement.setString(1,building);try(var rows=statement.executeQuery()){rows.next();assertThat(rows.getInt(1)).isGreaterThanOrEqualTo(9600);}
        }
        if(viaAdapter) {
            var expected=new HashSet<String>();
            try(var s=database.prepareStatement("SELECT r.canonical_message_id FROM biz_telemetry_receipt r JOIN biz_telemetry_message_intent i ON r.canonical_message_id=i.canonical_message_id WHERE r.building_id=? AND CAST(JSON_UNQUOTE(JSON_EXTRACT(i.snapshot_json,'$.message.electricityInterval.revision')) AS UNSIGNED)=?")) {
                s.setString(1,building);s.setLong(2,revision);try(var rows=s.executeQuery()){while(rows.next())expected.add(rows.getString(1));}
            }
            assertThat(expected).hasSize(9600);long until=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(!applicationAcks.containsAll(expected)&&System.nanoTime()<until)Thread.sleep(100);
            assertThat(applicationAcks).containsAll(expected);save("application-acks",Map.of("expectedMessages",9600,"allPeriodAcksObserved",true));
        }
        long before=task(95).revision();
        for(int meter=0;meter<100;meter++)publish(95,meter,revision,meter==0?"99":"1",true);
        revisions[0]=revision+1;publish(95,0,revision+1,"99",true);assertThat(awaitReady(95,revisions).revision()).isEqualTo(before+1);
        var wrong=payload(95,0,revision+1,"98",true);publish(0,wrong);
        wrong=payload(95,0,revision+1,"99",true);wrong.withObject("/electricityInterval").put("factorUnit","kgCO2e/kWh");publish(0,wrong);
        wrong=payload(95,0,revision+1,"99",true);wrong.withObject("/electricityInterval").put("pointId",fixture.path("points").get(1).path("pointId").asText());publish(0,wrong);
        wrong=payload(95,0,revision+1,"99",true);wrong.withObject("/deviceIdentity").put("value","UNKNOWN_MQTT_DEVICE");publish(0,wrong);
        publish(95,0,revision-1,"999",true);
        before=task(95).revision();revisions[2]=revision+1;publish(95,2,revision+1,"1",true);
        assertThat(awaitReady(95,revisions).revision()).isEqualTo(before+1);
        var beforeOther=task(94).resultId();
        publish(95,1,revision+2,"2",false);awaitSplit(95,1,revision+2);
        assertThat(api(path()+"?periodType=MONTH&period=2026-10",null).path("points").get(0).path("emissionTco2").isNull()).isTrue();
        revisions[0]=revision+2;publish(95,0,revision+2,"100",true);revisions[1]=revision+2;publish(95,1,revision+2,null,true);
        var corrected=awaitReady(95,revisions);assertThat(task(94).resultId()).isEqualTo(beforeOther);
        var evidence=api(path()+"/results/"+corrected.resultId()+"/evidence",null);
        for(var source:evidence.path("electricityEvidence"))assertThat(source.path("transport").asText()).isEqualTo(transport);
        checkResults();save("behavior",Map.of("duplicateNotCounted",true,"oldRevisionIgnored",true,"invalidMessagesRejected",true,"splitCorrection",true,"unaffectedIntervalRetained",true));
        System.out.println(viaAdapter?"INDEPENDENT_V2_ADAPTER_100_METER_CHAIN_OK":"INDEPENDENT_MQTT_100_METER_CHAIN_OK");
    }
}
