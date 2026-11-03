package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.platform.carbon.CarbonDisplayQueryContracts.*;

/** 无页面请求的主动登记、北京跨月及就绪业务边界；只用Mock/H2，不触及真实身份或建筑配置。 */
class CarbonDisplayPrewarmServiceTest {
    final CarbonDisplayQueryProperties properties=new CarbonDisplayQueryProperties();
    final CarbonDisplayTaskIdentity identity=mock(CarbonDisplayTaskIdentity.class);
    final EnergyPeriodAuthorization auth=mock(EnergyPeriodAuthorization.class);
    final CarbonDisplayQueryService display=mock(CarbonDisplayQueryService.class);
    final List<String> roles=List.of("BUILDING_OWNER");
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    JdbcTemplate jdbc;CarbonDisplayPrewarmService prewarm;
    @BeforeEach void setup(){
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:prewarm-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE building(building_id VARCHAR,building_code VARCHAR,del_flag INT)");
        jdbc.update("INSERT INTO building VALUES ('B','CXG',0)");
        properties.setPrewarmTargets(List.of(new CarbonDisplayQueryProperties.PrewarmTarget("CXG",7)));
        when(identity.currentRoles(7)).thenReturn(roles);
        prewarm=at("2026-11-02T00:00:00Z");
    }
    CarbonDisplayPrewarmService at(String time){return new CarbonDisplayPrewarmService(properties,identity,auth,display,jdbc,Clock.fixed(Instant.parse(time),ZoneOffset.UTC));}
    @Test void startupRegistersOnlyFourDefaultQueriesWithoutVisitorOrHeavyCalls(){
        prewarm.registerDefaults();var expected=prewarm.plans("B").stream().map(CarbonDisplayPrewarmService.Plan::query).toList();
        assertThat(expected).hasSize(4);assertThat(expected.get(0).throughMonth()).isEqualTo("2026-10");
        assertThat(expected.get(1).month()).isEqualTo(10);
        for(var query:expected)verify(display).prepare(7,roles,query);
        verifyNoMoreInteractions(display);verify(auth).checkBuilding(7,roles,"B");
    }
    @Test void monthlyRolloverUsesBeijingBoundaryAndJanuaryNeverFabricatesCurrentYearAccumulation(){
        var before=at("2026-10-31T15:59:59Z").plans("B");var after=at("2026-10-31T16:00:00Z").plans("B");
        assertThat(before.get(0).query().throughMonth()).isEqualTo("2026-09");assertThat(after.get(0).query().throughMonth()).isEqualTo("2026-10");
        prewarm=at("2026-12-31T16:00:00Z");var january=prewarm.plans("B");
        assertThat(january.get(0).query()).isNull();assertThat(january.get(1).query()).isNull();assertThat(january.get(2).query().period()).isEqualTo("2026-12");
        prewarm.registerDefaults();verify(display,times(2)).prepare(eq(7L),eq(roles),any());
    }
    @Test void revokedIdentityAndBuildingDenialCannotRegisterAutomaticWork(){
        when(identity.currentRoles(7)).thenThrow(new BusinessException(403,"revoked"));prewarm.registerDefaults();verifyNoInteractions(display);
        doReturn(roles).when(identity).currentRoles(7);doThrow(new BusinessException(403,"scope")).when(auth).checkBuilding(7,roles,"B");
        prewarm.registerDefaults();verifyNoInteractions(display);
    }
    @Test void oneRegistrationFailureDoesNotStarveOtherDefaultsOrAnotherBuilding(){
        jdbc.update("INSERT INTO building VALUES ('OTHER','OTHER',0)");properties.setPrewarmTargets(List.of(new CarbonDisplayQueryProperties.PrewarmTarget("CXG",7),new CarbonDisplayQueryProperties.PrewarmTarget("OTHER",7)));
        doThrow(new BusinessException(503,"source")).when(display).prepare(eq(7L),eq(roles),eq(prewarm.plans("B").getFirst().query()));
        prewarm.registerDefaults();verify(display,times(8)).prepare(eq(7L),eq(roles),any());
    }
    @Test void readinessIsReadOnlyAndRejectsUnauthorizedReadsBeforeInspectingTargets(){
        doThrow(new BusinessException(403,"denied")).when(auth).checkBuilding(1,roles,"B");
        assertThatThrownBy(()->prewarm.readiness(1,roles,"B")).isInstanceOf(BusinessException.class);verifyNoInteractions(display,identity);
    }
    @Test void technicalReadyCannotHideUnavailableBusinessValue()throws Exception {
        stubAvailable();var ytd=prewarm.plans("B").get(1).query();
        var unavailable=json.readValue("{\"actual\":{\"status\":\"UNAVAILABLE\"}}",CarbonIntensityContracts.View.class);
        when(display.inspect(1,roles,ytd)).thenReturn(view(ytd,unavailable));
        var result=prewarm.readiness(1,roles,"B");assertThat(result.ready()).isFalse();
        assertThat(result.items().get(1).state()).isEqualTo("READY");assertThat(result.items().get(1).errorCode()).isEqualTo("ACTUAL_UNAVAILABLE");
        verify(display,never()).prepare(anyLong(),anyCollection(),any());
    }
    @Test void allFourAvailableResultsPassWithoutRequiringAnnualComparabilityForYtdOrPercentageForMonth()throws Exception {
        stubAvailable();var result=prewarm.readiness(1,roles,"B");
        assertThat(result.ready()).isTrue();assertThat(result.items()).hasSize(4).allMatch(CarbonDisplayPrewarmService.Item::ready);
        verify(display,never()).prepare(anyLong(),anyCollection(),any());verify(identity).currentRoles(7);
    }
    @Test void unconfiguredAndInvalidDeploymentSettingsAreExplicitAndDoNotCreateAccountsOrTasks(){
        properties.setPrewarmTargets(List.of(new CarbonDisplayQueryProperties.PrewarmTarget("",0)));prewarm.registerDefaults();
        assertThat(prewarm.readiness(1,roles,"B").state()).isEqualTo("NOT_CONFIGURED");verifyNoInteractions(display,identity);
        properties.setPrewarmTargets(List.of(new CarbonDisplayQueryProperties.PrewarmTarget("CXG",0)));
        assertThat(prewarm.readiness(1,roles,"B").state()).isEqualTo("CONFIGURATION_ERROR");
    }
    @Test void missingResultsArePreparingWithoutVisitorRegistration(){
        for(var p:prewarm.plans("B")){var q=p.query();when(display.inspect(1,roles,q)).thenReturn(new View(CONTRACT,q.kind(),"B",q.key(),"source","PREPARING",null,1));}
        var result=prewarm.readiness(1,roles,"B");assertThat(result.state()).isEqualTo("PREPARING");assertThat(result.ready()).isFalse();
        verify(display,never()).prepare(anyLong(),anyCollection(),any());
    }
    @Test void disabledBackgroundActorCannotPassReadinessUsingAlreadyGeneratedResults()throws Exception {
        stubAvailable();when(identity.currentRoles(7)).thenThrow(new BusinessException(403,"CARBON_DISPLAY_ACTOR_DISABLED","revoked"));
        var result=prewarm.readiness(1,roles,"B");assertThat(result.ready()).isFalse();
        assertThat(result.items()).hasSize(4).allMatch(i->"CARBON_DISPLAY_ACTOR_DISABLED".equals(i.errorCode()));
        verifyNoInteractions(display);
    }
    private View view(Query q,Object value){return new View(CONTRACT,q.kind(),q.buildingId(),q.key(),"source","READY",value,0);}
    private void stubAvailable()throws Exception {
        var graph="{\"state\":\"READY\",\"total\":\"1\",\"nodes\":[{\"id\":\"A\"}],\"links\":[{\"source\":\"A\",\"target\":\"B\"}],\"unit\":\"%s\"}";
        var flow=json.readValue("{\"classificationBasis\":\"CONFIGURED_VIEW\",\"missingMonths\":[],\"carbon\":"+graph.formatted("tCO2e")+",\"energy\":"+graph.formatted("tce")+"}",JointFlowContracts.View.class);
        var intensity=json.readValue("""
                {"actual":{"status":"AVAILABLE","value":"4.79","unit":"kgCO2e/m²"},"comparisonStatus":"NOT_COMPARABLE","percentageStatus":"AVAILABLE","percentage":"8.4",
                "reference":{"rule":{"constraintValue":"56.71","baselineValue":"35.56","guideValue":"20.72"}}}
                """,CarbonIntensityContracts.View.class);
        for(var p:prewarm.plans("B"))when(display.inspect(1,roles,p.query())).thenReturn(view(p.query(),p.query().kind().equals("JOINT_FLOW")?flow:intensity));
        var month=prewarm.plans("B").getLast().query();
        var monthValue=json.readValue(json.writeValueAsString(intensity).replace("\"percentageStatus\":\"AVAILABLE\"","\"percentageStatus\":\"UNAVAILABLE\"").replace("\"percentage\":\"8.4\"","\"percentage\":null"),CarbonIntensityContracts.View.class);
        when(display.inspect(1,roles,month)).thenReturn(view(month,monthValue));
    }
}
