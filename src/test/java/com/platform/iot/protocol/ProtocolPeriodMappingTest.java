package com.platform.iot.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.onboarding.*;
import com.platform.iot.onboarding.api.DeviceProductContracts.*;
import com.platform.iot.protocol.api.ProtocolContracts.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProtocolPeriodMappingTest {
    private final Set<String> roles=Set.of("PLATFORM_ADMIN");
    private Mapping mapping(String code,String type,String unit,boolean required){return new Mapping("/"+code,code,unit,unit,BigDecimal.ONE,BigDecimal.ZERO,required,true,0,type);}
    private Configuration config(List<Mapping> mappings){return new Configuration("test","PRODUCT","METER","own/raw","SERIAL_NO","/id",null,null,null,mappings);}
    private Configuration configWithReliabilityFields(List<Mapping> mappings){return new Configuration("test","PRODUCT","METER","own/raw","SERIAL_NO","/id",null,null,
            "/time","/seq","/messageId","/bootId","/batchId","/retransmittedAt","EVIDENCE_ONLY","NONE",mappings);}
    private List<Mapping> mappings(){return List.of(mapping("Q","ELECTRICITY_KWH","kWh",false),mapping("F","CO2_FACTOR","kgCO2/kWh",false),mapping("start","INTERVAL_START","ms",true),mapping("end","INTERVAL_END","ms",true),mapping("revision","INTERVAL_REVISION","1",true));}
    private ProtocolDraftService service(ProtocolDraftRepository repository) {
        var products=mock(DeviceProductService.class);var product=mock(DetailView.class);var q=mock(PointTemplateView.class);
        when(products.detail("PRODUCT",roles)).thenReturn(product);when(product.expectedProfileCode()).thenReturn("METER");when(product.identityType()).thenReturn("SERIAL_NO");when(product.points()).thenReturn(List.of(q));
        when(q.enabled()).thenReturn(true);when(q.required()).thenReturn(true);when(q.metricCode()).thenReturn("Q");when(q.unit()).thenReturn("kWh");
        return new ProtocolDraftService(repository,products,mock(OnboardingAuditService.class),new ObjectMapper());
    }
    @Test void periodMetadataUsesExistingMappingAndPreviewPreservesPair() {
        var drafts=service(mock(ProtocolDraftRepository.class));var c=config(mappings());
        assertThatCode(()->drafts.validate(c,roles)).doesNotThrowAnyException();
        var preview=new ProtocolPreviewService(drafts,65536,20,1024).preview(new PreviewRequest(c,"{\"id\":\"SIM\",\"Q\":1.25,\"F\":0.5,\"start\":1790784000000,\"end\":1790784900000,\"revision\":1}",1790785000000L),roles);
        assertThat(preview.success()).isTrue();assertThat(preview.metrics()).allMatch(m->"PRESENT".equals(m.status()));
    }
    @Test void oldClientEditPreservesTypedRolesInsteadOfDowngrading() throws Exception {
        var repository=mock(ProtocolDraftRepository.class);var drafts=service(repository);var mapper=new ObjectMapper();
        when(repository.find("D")).thenReturn(Optional.of(new ProtocolDraftRepository.Row("D",1,mapper.writeValueAsString(config(mappings())),1)));
        when(repository.update(any(),eq(1L),eq(1L))).thenReturn(1);
        var old=mappings().stream().map(m->new Mapping(m.sourcePath(),m.metricCode(),m.sourceUnit(),m.targetUnit(),m.scale(),m.offset(),m.required(),m.enabled(),m.sortOrder())).toList();
        var result=drafts.update("D",new com.platform.iot.protocol.api.ProtocolContracts.UpdateRequest(1L,config(old)),1L,roles);
        assertThat(result.configuration().mappings()).extracting(Mapping::effectiveValueType).containsExactly("ELECTRICITY_KWH","CO2_FACTOR","INTERVAL_START","INTERVAL_END","INTERVAL_REVISION");
    }

    @Test void oldClientEditPreservesReliabilityFieldsInsteadOfClearingThem() throws Exception {
        var repository=mock(ProtocolDraftRepository.class);var drafts=service(repository);var mapper=new ObjectMapper();
        var stored=configWithReliabilityFields(mappings());
        when(repository.find("D")).thenReturn(Optional.of(new ProtocolDraftRepository.Row("D",1,mapper.writeValueAsString(stored),1)));
        when(repository.update(any(),eq(1L),eq(1L))).thenReturn(1);
        var result=drafts.update("D",new com.platform.iot.protocol.api.ProtocolContracts.UpdateRequest(1L,config(mappings())),1L,roles);
        assertThat(result.configuration().seqPath()).isEqualTo("/seq");
        assertThat(result.configuration().messageIdPath()).isEqualTo("/messageId");
        assertThat(result.configuration().bootIdPath()).isEqualTo("/bootId");
        assertThat(result.configuration().batchIdPath()).isEqualTo("/batchId");
        assertThat(result.configuration().retransmittedAtPath()).isEqualTo("/retransmittedAt");
        assertThat(result.configuration().maxAckMode()).isEqualTo("EVIDENCE_ONLY");
        assertThat(result.configuration().correlationPolicy()).isEqualTo("NONE");
    }

    @Test void previewPassesReliabilityFieldsToTheSharedParser() {
        var drafts=service(mock(ProtocolDraftRepository.class));
        var c=configWithReliabilityFields(mappings());
        var preview=new ProtocolPreviewService(drafts,65536,20,1024).preview(new PreviewRequest(c,
                "{\"id\":\"SIM\",\"time\":1790784000000,\"seq\":7,\"messageId\":\"M-7\",\"bootId\":\"B-1\",\"batchId\":\"BT-1\",\"retransmittedAt\":1790784010000,\"Q\":1.25,\"F\":0.5,\"start\":1790784000000,\"end\":1790784900000,\"revision\":1}",
                1790785000000L),roles);
        assertThat(preview.success()).isTrue();
        assertThat(preview.timeSource()).isEqualTo("DEVICE_REPORTED");
        assertThat(preview.eventTime()).isEqualTo(1790784000000L);
    }
    @Test void previewRejectsCorrelationDeclarationWithoutSourceFields() {
        var old=config(mappings());
        var c=new Configuration(old.name(),old.productId(),old.profileCode(),old.sourceTopic(),old.identityType(),old.identityPath(),
                old.discriminatorPath(),old.discriminatorValue(),old.timestampPath(),null,null,null,null,null,"ADAPTER_PROXY","BOOT_ID_AND_SEQ",old.mappings());
        var preview=new ProtocolPreviewService(service(mock(ProtocolDraftRepository.class)),65536,20,1024).preview(new PreviewRequest(c,"{}",1790785000000L),roles);
        assertThat(preview.success()).isFalse();assertThat(preview.errors().getFirst().code()).isEqualTo("CORRELATION_FIELD_MISSING");
    }
}
