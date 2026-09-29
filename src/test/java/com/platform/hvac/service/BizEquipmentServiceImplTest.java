package com.platform.hvac.service;

import com.platform.framework.exception.BusinessException;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.hvac.mapper.BizEquipmentTypeMapper;
import com.platform.hvac.mapper.BizSpaceMapper;
import com.platform.hvac.mapper.BizSystemGroupMapper;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.model.entity.BizEquipmentType;
import com.platform.hvac.model.entity.BizSpace;
import com.platform.hvac.model.entity.BizSystemGroup;
import com.platform.hvac.service.impl.BizEquipmentServiceImpl;
import com.platform.iot.onboarding.mapper.BizDeviceProductMapper;
import com.platform.iot.onboarding.model.entity.BizDeviceProduct;
import com.platform.relation.RelationGovernanceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BizEquipmentServiceImplTest {

    private static final String TYPE_CODE = "WCR";
    private static final String PRODUCT_ID = "PRODUCT_1";

    @Mock
    private BizEquipmentMapper equipmentMapper;
    @Mock
    private BizEquipmentTypeMapper equipmentTypeMapper;
    @Mock
    private BizDeviceProductMapper productMapper;
    @Mock
    private BizSystemGroupMapper systemGroupMapper;
    @Mock
    private BizSpaceMapper spaceMapper;
    @Mock
    private RelationGovernanceGuard relationGuard;

    private BizEquipmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BizEquipmentServiceImpl(equipmentTypeMapper, productMapper, systemGroupMapper,
                spaceMapper, new EquipmentCodeAllocator(), relationGuard);
        ReflectionTestUtils.setField(service, "baseMapper", equipmentMapper);
    }

    private void stubEnabledEquipmentType() {
        BizEquipmentType type = new BizEquipmentType();
        type.setStatus(1);
        type.setAssetCodePrefix("WCR");
        type.setEquipCategory("CHILLER");
        when(equipmentTypeMapper.selectById(TYPE_CODE)).thenReturn(type);
    }

    @Test
    void rejectsMissingProductWhenProductWasSelected() {
        stubEnabledEquipmentType();
        BizEquipment equipment = newEquipment();
        equipment.setProductId(PRODUCT_ID);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);

        assertInvalidProduct(equipment);

        verify(equipmentMapper, never()).insert(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "DISABLED"})
    void rejectsProductThatIsNotEnabled(String status) {
        stubEnabledEquipmentType();
        BizEquipment equipment = newEquipment();
        equipment.setProductId(PRODUCT_ID);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product(status, TYPE_CODE));

        assertInvalidProduct(equipment);

        verify(equipmentMapper, never()).insert(any());
    }

    @Test
    void rejectsProductWhoseEquipmentTypeDiffersFromSubmittedType() {
        stubEnabledEquipmentType();
        BizEquipment equipment = newEquipment();
        equipment.setProductId(PRODUCT_ID);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product("ENABLED", "WCP"));

        assertInvalidProduct(equipment);

        verify(equipmentMapper, never()).insert(any());
    }

    @Test
    void acceptsEnabledProductWithMatchingType() {
        stubEnabledEquipmentType();
        BizEquipment equipment = newEquipment();
        equipment.setProductId(PRODUCT_ID);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product("ENABLED", TYPE_CODE));
        BizSystemGroup group = new BizSystemGroup();
        group.setBuildingId("BUILDING_1");
        when(systemGroupMapper.selectById("GROUP_1")).thenReturn(group);
        BizSpace space = new BizSpace();
        space.setBuildingId("BUILDING_1");
        when(spaceMapper.selectById("SPACE_1")).thenReturn(space);
        when(equipmentMapper.selectHistoricalCodes("BUILDING_1", TYPE_CODE)).thenReturn(List.of());
        when(equipmentMapper.insert(equipment)).thenReturn(1);

        service.add(equipment);

        assertThat(equipment.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(equipment.getEquipCategory()).isEqualTo("CHILLER");
        verify(equipmentMapper).insert(equipment);
    }

    @Test
    void allowsLegacyAddWithoutProductBinding() {
        stubEnabledEquipmentType();
        BizEquipment equipment = newEquipment();
        when(systemGroupMapper.selectById("GROUP_1")).thenReturn(groupFor("BUILDING_1"));
        when(spaceMapper.selectById("SPACE_1")).thenReturn(spaceFor("BUILDING_1"));
        when(equipmentMapper.selectHistoricalCodes("BUILDING_1", TYPE_CODE)).thenReturn(List.of());
        when(equipmentMapper.insert(equipment)).thenReturn(1);

        service.add(equipment);

        assertThat(equipment.getEquipCategory()).isEqualTo("CHILLER");
        verify(productMapper, never()).selectById(any());
        verify(equipmentMapper).insert(equipment);
    }

    @Test
    void ordinaryUpdatePreservesExistingProductBinding() {
        BizEquipment existing = newEquipment();
        existing.setEquipId("EQUIPMENT_1");
        existing.setEquipCode("WCR1");
        existing.setEquipCategory("CHILLER");
        existing.setProductId(PRODUCT_ID);
        when(equipmentMapper.selectById("EQUIPMENT_1")).thenReturn(existing);
        when(equipmentMapper.updateById(any())).thenReturn(1);
        BizEquipment update = new BizEquipment();
        update.setEquipId("EQUIPMENT_1");
        update.setProductId("OTHER_PRODUCT");
        update.setSystemGroupId(existing.getSystemGroupId());
        update.setSpaceId(existing.getSpaceId());

        when(systemGroupMapper.selectById("GROUP_1")).thenReturn(groupFor("BUILDING_1"));
        when(spaceMapper.selectById("SPACE_1")).thenReturn(spaceFor("BUILDING_1"));

        service.update(update);

        assertThat(update.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(update.getTypeCode()).isEqualTo(TYPE_CODE);
        verify(equipmentMapper).updateById(update);
        verify(productMapper, never()).selectById(any());
    }

    private void assertInvalidProduct(BizEquipment equipment) {
        assertThatThrownBy(() -> service.add(equipment))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(400));
    }

    private BizEquipment newEquipment() {
        BizEquipment equipment = new BizEquipment();
        equipment.setTypeCode(TYPE_CODE);
        equipment.setBuildingId("BUILDING_1");
        equipment.setSystemGroupId("GROUP_1");
        equipment.setSpaceId("SPACE_1");
        return equipment;
    }

    private BizDeviceProduct product(String status, String equipmentTypeCode) {
        BizDeviceProduct product = new BizDeviceProduct();
        product.setProductId(PRODUCT_ID);
        product.setStatus(status);
        product.setEquipmentTypeCode(equipmentTypeCode);
        return product;
    }

    private BizSystemGroup groupFor(String buildingId) {
        BizSystemGroup group = new BizSystemGroup();
        group.setBuildingId(buildingId);
        return group;
    }

    private BizSpace spaceFor(String buildingId) {
        BizSpace space = new BizSpace();
        space.setBuildingId(buildingId);
        return space;
    }
}
