package com.platform.iot.deviceparameter.importing;

import com.platform.iot.deviceparameter.DeviceParameterAuthorization;
import com.platform.iot.deviceparameter.DeviceParameterCandidateService;
import com.platform.iot.deviceparameter.DeviceParameterCandidateService.CandidateCommand;
import com.platform.iot.deviceparameter.DeviceParameterCandidateService.CandidateValidation;
import com.platform.iot.deviceparameter.DeviceParameterErrors;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.ImportBatch;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.ImportRow;
import com.platform.iot.deviceparameter.DeviceParameterModels.Candidate;
import com.platform.iot.deviceparameter.DeviceParameterModels.EquipmentIdentity;
import com.platform.iot.deviceparameter.DeviceParameterModels.SourceType;
import com.platform.iot.deviceparameter.DeviceParameterModels.ValidationStatus;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.ImportBatchView;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.ImportRowView;
import lombok.RequiredArgsConstructor;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.platform.iot.deviceparameter.DeviceParameterSupport.id;
import static com.platform.iot.deviceparameter.DeviceParameterSupport.sha256;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/**
 * 固定模板 .xlsx 的“上传校验 → 明确确认”两阶段导入服务。
 *
 * <p>校验阶段只保存脱敏批次和行级结果，不创建候选。确认阶段重新校验建筑权限、批次状态和
 * 正式归一化规则，并在单个 MySQL 事务中生成全部 EXCEL 候选；任一失败整批回滚。</p>
 */
public class DeviceParameterExcelImportService {
    private static final List<String> HEADERS = List.of(
            "equipmentCode", "parameterCode", "value", "unit", "observedAt", "sourceReference");

    private final DeviceParameterJdbcRepository repository;
    private final DeviceParameterCandidateService candidateService;
    private final DeviceParameterAuthorization authorization;
    private final DeviceParameterImportProperties properties;

    @Transactional
    public ImportBatchView validate(
            long userId, Collection<String> roles, String buildingId, MultipartFile file) {
        authorization.requireMaintainer(roles);
        authorization.checkBuilding(userId, roles, buildingId);
        byte[] bytes = readFile(file);
        String hash = sha256Bytes(bytes);
        ImportBatch repeated = repository.findImportBatchByHash(buildingId, hash).orElse(null);
        if (repeated != null) {
            return view(repeated, repository.listImportRows(repeated.batchId()));
        }
        String batchId = id();
        String safeName = safeFileName(file.getOriginalFilename());
        ParseResult parsed;
        try {
            parsed = parse(buildingId, bytes, hash);
        } catch (ImportRejectedException exception) {
            ImportBatch rejected = new ImportBatch(batchId, buildingId, safeName, hash,
                    "REJECTED", 0, 0, 1, exception.code(), userId, null,
                    LocalDateTime.now(), null);
            repository.insertImportBatch(rejected);
            repository.audit(id(), buildingId, "USER", userId, "VALIDATE_IMPORT",
                    "IMPORT_BATCH", batchId, null, null, "REJECTED", "REJECTED",
                    exception.code(), null, hash);
            return view(rejected, List.of());
        }
        int errors = (int) parsed.rows().stream().filter(row -> "INVALID".equals(row.status())).count();
        int valid = parsed.rows().size() - errors;
        String status = errors == 0 && !parsed.rows().isEmpty() ? "VALIDATED" : "REJECTED";
        String summary = errors == 0 ? null : "IMPORT_ROWS_INVALID:" + errors;
        ImportBatch batch = new ImportBatch(batchId, buildingId, safeName, hash, status,
                parsed.rows().size(), valid, errors, summary, userId, null,
                LocalDateTime.now(), null);
        repository.insertImportBatch(batch);
        repository.insertImportRows(batchId, parsed.rows());
        repository.audit(id(), buildingId, "USER", userId, "VALIDATE_IMPORT",
                "IMPORT_BATCH", batchId, null, null, status, "SUCCESS", summary,
                null, hash);
        return view(batch, parsed.rows());
    }

    @Transactional
    public ImportBatchView confirm(
            long userId, Collection<String> roles, String batchId) {
        authorization.requireMaintainer(roles);
        ImportBatch batch = repository.findImportBatch(batchId, true)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "Excel 导入批次不存在"));
        authorization.checkBuilding(userId, roles, batch.buildingId());
        if ("IMPORTED".equals(batch.status())) {
            return view(batch, repository.listImportRows(batchId));
        }
        if (!"VALIDATED".equals(batch.status()) || batch.errorCount() != 0) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.IMPORT_REJECTED,
                    "只有全部行通过校验的批次可以确认导入");
        }
        List<ImportRow> rows = repository.listImportRows(batchId);
        if (rows.size() != batch.validRowCount() || rows.isEmpty()) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.IMPORT_REJECTED,
                    "导入批次行数或校验摘要不一致");
        }
        List<Candidate> created = new ArrayList<>();
        for (ImportRow row : rows) {
            EquipmentIdentity equipment = repository.findEquipmentByCode(
                    batch.buildingId(), row.equipmentCode()).orElseThrow(() ->
                    DeviceParameterErrors.error(409, DeviceParameterErrors.IMPORT_REJECTED,
                            "确认时设备档案已变化"));
            created.add(candidateService.ingest(command(
                    equipment, batch, row, userId)));
        }
        if (repository.markImportConfirmed(batchId, userId) != 1) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.IMPORT_REJECTED,
                    "导入批次已被并发处理");
        }
        repository.audit(id(), batch.buildingId(), "USER", userId, "CONFIRM_IMPORT",
                "IMPORT_BATCH", batchId, null, null, "candidates=" + created.size(),
                "SUCCESS", null, null, batch.fileSha256());
        return view(repository.findImportBatch(batchId, false).orElseThrow(), rows);
    }

    public ImportBatchView detail(
            long userId, Collection<String> roles, String batchId) {
        authorization.requireMaintainer(roles);
        ImportBatch batch = repository.findImportBatch(batchId, false)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "Excel 导入批次不存在"));
        authorization.checkBuilding(userId, roles, batch.buildingId());
        return view(batch, repository.listImportRows(batchId));
    }

    private ParseResult parse(String buildingId, byte[] bytes, String fileHash) {
        try (OPCPackage pack = OPCPackage.open(new ByteArrayInputStream(bytes));
             XSSFWorkbook workbook = new XSSFWorkbook(pack)) {
            boolean macro = pack.getParts().stream().map(PackagePart::getPartName)
                    .map(Object::toString).anyMatch(name -> name.toLowerCase(Locale.ROOT)
                            .contains("vbaproject"));
            if (macro || !workbook.getExternalLinksTable().isEmpty()) {
                throw rejected("IMPORT_MACRO_OR_EXTERNAL_LINK_REJECTED");
            }
            if (workbook.getNumberOfSheets() != 1) {
                throw rejected("IMPORT_WORKBOOK_SHAPE_INVALID");
            }
            Sheet sheet = workbook.getSheetAt(0);
            validateHeaders(sheet.getRow(0));
            if (sheet.getLastRowNum() > properties.getMaxRows()) {
                throw rejected("IMPORT_ROW_LIMIT_EXCEEDED");
            }
            List<ImportRow> rows = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || empty(row)) {
                    continue;
                }
                int rowNo = index + 1;
                try {
                    rejectFormulaCells(row);
                    String equipmentCode = text(row.getCell(0));
                    String parameterCode = code(row.getCell(1));
                    String rawValue = numberText(row.getCell(2));
                    String unit = code(row.getCell(3));
                    LocalDateTime observedAt = dateTime(row.getCell(4));
                    String sourceReference = text(row.getCell(5));
                    String duplicateKey = equipmentCode + '|' + parameterCode;
                    if (!identities.add(duplicateKey)) {
                        rows.add(invalidRow(rowNo, equipmentCode, parameterCode, rawValue, unit,
                                observedAt, sourceReference, "parameterCode", "IMPORT_DUPLICATE_ROW"));
                        continue;
                    }
                    if (overLimit(equipmentCode, parameterCode, rawValue, unit, sourceReference)) {
                        rows.add(invalidRow(rowNo, equipmentCode, parameterCode, rawValue, unit,
                                observedAt, sourceReference, "row", "IMPORT_TEXT_LIMIT_EXCEEDED"));
                        continue;
                    }
                    EquipmentIdentity equipment = repository.findEquipmentByCode(
                            buildingId, equipmentCode).orElse(null);
                    if (equipment == null) {
                        rows.add(invalidRow(rowNo, equipmentCode, parameterCode, rawValue, unit,
                                observedAt, sourceReference, "equipmentCode", "IMPORT_EQUIPMENT_NOT_FOUND"));
                        continue;
                    }
                    ImportBatch virtualBatch = new ImportBatch(fileHash, buildingId, "", fileHash,
                            "VALIDATING", 0, 0, 0, null, 0, null, null, null);
                    ImportRow pending = new ImportRow(rowNo, equipmentCode, parameterCode, rawValue,
                            unit, observedAt, sourceReference, "READY", null, null);
                    CandidateValidation validation = candidateService.validateOnly(
                            command(equipment, virtualBatch, pending, null));
                    if (validation.status() != ValidationStatus.READY) {
                        rows.add(invalidRow(rowNo, equipmentCode, parameterCode, rawValue, unit,
                                observedAt, sourceReference, "value", validation.reason()));
                    } else {
                        rows.add(pending);
                    }
                } catch (ImportRejectedException exception) {
                    rows.add(invalidRow(rowNo, text(row.getCell(0)), code(row.getCell(1)),
                            numberText(row.getCell(2)), code(row.getCell(3)), null,
                            text(row.getCell(5)), "row", exception.code()));
                }
            }
            return new ParseResult(List.copyOf(rows));
        } catch (ImportRejectedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected("IMPORT_FILE_INVALID_OR_ENCRYPTED");
        }
    }

    private CandidateCommand command(
            EquipmentIdentity equipment, ImportBatch batch, ImportRow row, Long userId) {
        return new CandidateCommand(equipment, SourceType.EXCEL, batch.batchId(),
                batch.fileSha256(), row.parameterCode(), row.rawValue(), row.rawUnit(),
                null, row.parameterCode(), row.observedAt(),
                batch.fileSha256() + '|' + row.rowNo(), userId);
    }

    private byte[] readFile(MultipartFile file) {
        if (file == null || file.isEmpty()
                || file.getSize() > properties.getMaxFileBytes()
                || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw DeviceParameterErrors.error(400, DeviceParameterErrors.IMPORT_REJECTED,
                    "只接受大小受限的无宏 .xlsx 文件");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw DeviceParameterErrors.error(400, DeviceParameterErrors.IMPORT_REJECTED,
                    "Excel 文件读取失败");
        }
    }

    private void validateHeaders(Row header) {
        if (header == null) {
            throw rejected("IMPORT_HEADER_INVALID");
        }
        for (int index = 0; index < HEADERS.size(); index++) {
            Cell cell = header.getCell(index);
            if (cell == null || cell.getCellType() == CellType.FORMULA
                    || !HEADERS.get(index).equals(text(cell))) {
                throw rejected("IMPORT_HEADER_INVALID");
            }
        }
    }

    private void rejectFormulaCells(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() == CellType.FORMULA) {
                throw rejected("IMPORT_FORMULA_CELL_REJECTED");
            }
        }
    }

    private boolean overLimit(String... values) {
        for (String value : values) {
            if (value != null && value.length() > properties.getMaxTextLength()) {
                return true;
            }
        }
        return false;
    }

    private static boolean empty(Row row) {
        for (int index = 0; index < HEADERS.size(); index++) {
            Cell cell = row.getCell(index);
            if (cell != null && cell.getCellType() != CellType.BLANK
                    && !text(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String text(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> NumberToTextConverter.toText(cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private static String code(Cell cell) {
        String value = text(cell);
        return value.isBlank() ? value : value.toUpperCase(Locale.ROOT);
    }

    private static String numberText(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return NumberToTextConverter.toText(cell.getNumericCellValue());
        }
        return text(cell);
    }

    private static LocalDateTime dateTime(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue();
        }
        String value = text(cell);
        try {
            return value.isBlank() ? null : LocalDateTime.parse(value);
        } catch (RuntimeException exception) {
            throw rejected("IMPORT_OBSERVED_AT_INVALID");
        }
    }

    private ImportBatchView view(ImportBatch batch, List<ImportRow> rows) {
        List<String> actions = "VALIDATED".equals(batch.status())
                ? List.of("CONFIRM") : List.of();
        return new ImportBatchView(batch.batchId(), batch.buildingId(), batch.safeFileName(),
                batch.status(), batch.rowCount(), batch.validRowCount(), batch.errorCount(),
                batch.errorSummary(), rows.stream().map(row -> new ImportRowView(row.rowNo(),
                row.equipmentCode(), row.parameterCode(), row.rawValue(), row.rawUnit(),
                row.status(), row.errorField(), row.errorCode())).toList(), actions);
    }

    private static ImportRow invalidRow(
            int rowNo, String equipmentCode, String parameterCode, String rawValue,
            String unit, LocalDateTime observedAt, String sourceReference,
            String field, String code) {
        return new ImportRow(rowNo, equipmentCode, parameterCode, rawValue, unit,
                observedAt, sourceReference, "INVALID", field, code);
    }

    private static String safeFileName(String name) {
        String value = name == null ? "upload.xlsx" : name.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "_").trim();
        return value.isBlank() ? "upload.xlsx" : value.substring(0, Math.min(255, value.length()));
    }

    private static String sha256Bytes(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static ImportRejectedException rejected(String code) {
        return new ImportRejectedException(code);
    }

    private record ParseResult(List<ImportRow> rows) {
    }

    private static final class ImportRejectedException extends RuntimeException {
        private final String code;

        private ImportRejectedException(String code) {
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
