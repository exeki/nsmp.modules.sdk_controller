package ru.kazantsev.nsmp.modules.sdk_controller

import com.fasterxml.jackson.databind.ObjectMapper
import groovy.transform.Field
import ru.kazantsev.nsmp.modules.web_api_components.Preferences
import ru.kazantsev.nsmp.modules.web_api_components.WebApiUtilities
import ru.naumen.core.server.script.api.injection.InjectApi
import ru.naumen.metainfo.shared.IClassFqn

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static ru.kazantsev.nsd.sdk.global_variables.ApiPlaceholder.*

import ru.kazantsev.nsmp.modules.web_api_components.RequestProcessor
import ru.kazantsev.nsmp.modules.web_api_components.WebApiException

import ru.naumen.core.server.script.api.metainfo.IMetaClassWrapper
import ru.naumen.core.shared.dto.ISDtObject

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import ru.naumen.core.server.script.modules.storage.ScriptModulesStorageService;
import ru.naumen.core.server.script.storage.ScriptStorageService;
import ru.naumen.core.server.script.modules.storage.ScriptModule
import ru.naumen.core.server.SpringContext
import ru.naumen.commons.server.utils.MessageDigestUtils;
import ru.naumen.core.server.script.modules.storage.ScriptContainer
import ru.naumen.metainfo.shared.script.Script
import ru.naumen.advimport.shared.ImportConfigContainer
import ru.naumen.core.server.metastorage.impl.metainfo.MetaStorageService

@Field Preferences prefs = new Preferences().assertSuperuser(true)

class Constants {
    static final List<String> HAS_RELATED_METACLASS_CODES = ['catalogItem', 'catalogItemSet', 'backBOLinks', 'object', 'boLinks']
}

class Utilities {
    static String getChecksum(String str) {
        MessageDigestUtils.sha256WithStandardSalt(str)
    }

    static getObjectMapper() {
        return new ObjectMapper()
    }

    static void putZipEntry(ZipOutputStream zos, String entryName, byte[] entryBody) {
        ZipEntry entry = new ZipEntry(entryName)
        entry.setSize((long) entryBody.length)
        zos.putNextEntry(entry)
        zos.write(entryBody)
        zos.closeEntry()
    }

}

@InjectApi
class BranchCollector {
    @InjectApi
    static class UniquenessContainer {
        List<IMetaClassWrapper> collected = []
        List<String> detectedCodes = []

        Boolean add(IMetaClassWrapper metaClassWrapper) {
            if (metaClassWrapper.toString() in this.detectedCodes) return false
            this.detectedCodes.add(metaClassWrapper.toString())
            this.collected.add(metaClassWrapper)
            return true
        }

        Boolean add(IClassFqn fqn) {
            return this.add(api.metainfo.getMetaClass(fqn))
        }

        Boolean add(String metaClassCode) {
            return this.add(api.metainfo.getMetaClass(fqn))
        }
    }

    UniquenessContainer container = new UniquenessContainer()

    List<IMetaClassWrapper> process(String someMetaCode) {
        IMetaClassWrapper meta = api.metainfo.getMetaClass(someMetaCode)
        if (meta == null) return null
        return process(meta)
    }

    List<IMetaClassWrapper> process(IClassFqn someFqn) {
        IMetaClassWrapper meta = api.metainfo.getMetaClass(someFqn)
        if (meta == null) return null
        return process(meta)
    }

    List<IMetaClassWrapper> process(IMetaClassWrapper someMetaCodeWrapper) {
        if (container.add(someMetaCodeWrapper)) {
            List attrMetaClasses = someMetaCodeWrapper.getAttributes().findAll { it.getType().code in Constants.HAS_RELATED_METACLASS_CODES }.collect { it.getType().getRelatedMetaClass() }
            attrMetaClasses.each {
                if (it == null) return;
                process(it)
            }
            someMetaCodeWrapper.getChildren().each {
                if (it == null) return;
                process(it)
            }
        }
        return container.collected
    }
}

class SrcService {
    SpringContext context = SpringContext.getInstance()
    ScriptStorageService scriptStorageService = context.getBean(ScriptStorageService)
    ScriptModulesStorageService scriptModulesStorageService = context.getBean(ScriptModulesStorageService)
    MetaStorageService metaStorageService = context.getBean(MetaStorageService)

    ImportConfigContainer getAdvImport(String uuid) {
        return metaStorageService.get('advimport', 'testImport1')
    }

    List<ImportConfigContainer> getAllAdvImports() {
        return metaStorageService.get('advimport')
    }

    Map<String, ImportConfigContainer> getAdvImports(Boolean all, List<String> uuids) {
        Map<String, ImportConfigContainer> map = [:]
        if (all) {
            getAllAdvImports().each {
                map.put(it.getUUID(), it)
            }
        } else {
            uuids.each {
                map.put(it, getAdvImport(it))
            }
        }
        return map
    }

    ru.naumen.metainfo.shared.script.Script getScript(String code) {
        def obj = scriptStorageService.getScript(code)
        if (obj == null) throw new WebApiException.BadRequest("Script ${code} not found")
        return obj
    }

    List<ru.naumen.metainfo.shared.script.Script> getAllScripts() {
        return scriptStorageService.getScripts()
    }

    Map<String, ru.naumen.metainfo.shared.script.Script> getScripts(Boolean all, List<String> codes) {
        Map<String, ru.naumen.metainfo.shared.script.Script> scriptMap = [:]
        if (all) {
            getAllScripts().each {
                scriptMap.put(it.code, it)
            }
        } else {
            codes.each {
                scriptMap.put(it, getScript(it))
            }
        }
        return scriptMap
    }

    ScriptModule getModule(String code) {
        def obj = scriptModulesStorageService.getModule(code).orElse(null)
        if (obj == null) throw new WebApiException.BadRequest("Module ${code} not found")
        return obj
    }

    List<ScriptModule> getAllModules() {
        return scriptModulesStorageService.getUserModules()
    }

    Map<String, ScriptModule> getModules(Boolean all, List<String> codes) {
        Map<String, ScriptModule> modules = [:]
        if (all) {
            getAllModules().each {
                modules.put(it.code, it)
            }
        } else {
            codes.each {
                modules.put(it, getModule(it))
            }
        }
        return modules
    }

}

class Dto {
    static class MetaClassWrapperDto implements Serializable {
        String title
        String fullCode
        String caseCode
        String classCode
        String parent
        String description
        Boolean hasResponsible
        Boolean hasWorkflow
        Boolean hardcoded
        List<String> children
        List<AttributeDto> attributes
        List<AttributeGroupDto> attributeGroups
    }

    static class AttributeDto implements Serializable {
        String title
        String code
        String type
        Boolean hardcoded
        Boolean required
        Boolean requiredInInterface
        Boolean unique
        String relatedMetaClass
        String description
    }

    static class AttributeGroupDto implements Serializable {
        String title
        String code
        List<String> attributes
    }

    static class SrcRequest implements Serializable {
        List<String> modules
        Boolean allModules
        List<String> scripts
        Boolean allScripts
        List<String> advImports
        Boolean allAdvImports
    }

    static class SrcInfo {
        String checksum
        String code
    }

    static class SrcInfoRoot {
        List<SrcInfo> modules
        List<SrcInfo> scripts
        List<SrcInfo> advImports
    }
}

@SuppressWarnings('GrMethodMayBeStatic')
private Dto.MetaClassWrapperDto getDtoFromMetaClassWrapper(IMetaClassWrapper metaClassWrapper) {
    return new Dto.MetaClassWrapperDto(
            fullCode: metaClassWrapper.getFqn().toString(),
            classCode: metaClassWrapper.getFqn().getId(),
            caseCode: metaClassWrapper.getFqn().getCase(),
            parent: metaClassWrapper.getParent().toString(),
            children: metaClassWrapper.getChildren().collect { it.toString() },
            title: metaClassWrapper.getTitle(),
            description: metaClassWrapper.getDescription(),
            hardcoded: metaClassWrapper.isHardcoded(),
            hasResponsible: metaClassWrapper.isHasResponsible(),
            hasWorkflow: metaClassWrapper.isHasResponsible(),
            attributes: metaClassWrapper.getAttributes().collect {
                return new Dto.AttributeDto(
                        title: it.getTitle(),
                        code: it.getCode(),
                        type: it.getType().code,
                        hardcoded: it.isHardcoded(),
                        required: it.isRequired(),
                        requiredInInterface: it.isRequiredInInterface(),
                        unique: it.isUnique(),
                        relatedMetaClass: (it.getType().code in Constants.HAS_RELATED_METACLASS_CODES) ? it.getType().getRelatedMetaClass().getId() : null,
                        description: it.getDescription()
                )
            },
            attributeGroups: metaClassWrapper.getAttributeGroups().collect {
                new Dto.AttributeGroupDto(
                        title: it.getTitle(),
                        code: it.getCode(),
                        attributes: it.getAttributeCodes()
                )
            }
    )
}

@SuppressWarnings("unused")
void getMetaClassBranchInfo(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, prefs.copy().assertHttpMethod('GET')).process {
        WebApiUtilities webApiUtilities ->
            String meta = webApiUtilities.getParamElseThrow("meta")
            IMetaClassWrapper metaClassWrapper = api.metainfo.getMetaClass(meta)
            if (metaClassWrapper == null) throw new WebApiException.BadRequest("Metaclass named $meta from url parameter not exists")
            BranchCollector collector = new BranchCollector()
            webApiUtilities.setBodyAsJson(collector.process(metaClassWrapper).collect { getDtoFromMetaClassWrapper(it) })
    }
}

@SuppressWarnings("unused")
void getMetaClassBranchesInfo(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, prefs.copy().assertHttpMethod('GET')).process {
        WebApiUtilities webApiUtilities ->
            String metasStr = webApiUtilities.getParamElseThrow("metas")
            List<String> metas = metasStr.split(',')
            BranchCollector collector = new BranchCollector()
            metas.each { meta ->
                IMetaClassWrapper metaClassWrapper = api.metainfo.getMetaClass(meta)
                if (metaClassWrapper == null) throw new WebApiException.BadRequest("Metaclass named $meta from url parameter not exists")
                collector.process(metaClassWrapper)
            }
            webApiUtilities.setBodyAsJson(collector.container.collected.collect { getDtoFromMetaClassWrapper(it) })
    }
}

@SuppressWarnings("unused")
void getMetaClassInfo(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, prefs.copy().assertHttpMethod('GET')).process {
        WebApiUtilities webApiUtilities ->
            String meta = webApiUtilities.getParamElseThrow("meta")
            IMetaClassWrapper metaClassWrapper = api.metainfo.getMetaClass(meta)
            if (metaClassWrapper == null) throw new WebApiException.NotFound("Cant find metaclass named $meta")
            webApiUtilities.setBodyAsJson(getDtoFromMetaClassWrapper(metaClassWrapper))
    }
}

@SuppressWarnings("unused")
void getSrc(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, prefs.copy().assertHttpMethod('POST')).process {
        WebApiUtilities webApiUtilities ->
            Dto.SrcRequest body = webApiUtilities.getBodyAsJsonElseThrow(Dto.SrcRequest)
            SrcService srcService = new SrcService()
            Map<String, ScriptModule> modules = srcService.getModules(body.allModules, body.modules)
            Map<String, ru.naumen.metainfo.shared.script.Script> scripts = srcService.getScripts(body.allScripts, body.scripts)
            Map<String, ImportConfigContainer> advImports = srcService.getAdvImports(body.allAdvImports, body.advImports)
            List<Dto.SrcInfo> modulesInfo = []
            List<Dto.SrcInfo> scriptsInfo = []
            List<Dto.SrcInfo> advImportsInfo = []
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (ZipOutputStream zipStream = new ZipOutputStream(outputStream)) {
                advImports.each { code, object ->
                    String content = object.getConfigContainer().getConfig()
                    advImportsInfo.add(
                            new Dto.SrcInfo(
                                    checksum: Utilities.getChecksum(content),
                                    code: code
                            )
                    )
                    Utilities.putZipEntry(zipStream, "advImport\\" + code + ".xml", content.bytes)
                }
                modules.each { code, object ->
                    String content = object.getScriptElement().getBody()
                    modulesInfo.add(
                            new Dto.SrcInfo(
                                    checksum: Utilities.getChecksum(content),
                                    code: code
                            )
                    )
                    Utilities.putZipEntry(zipStream, "modules\\" + code + ".groovy", content.bytes)
                }
                scripts.each { code, object ->
                    String content = object.getBody()
                    scriptsInfo.add(
                            new Dto.SrcInfo(
                                    checksum: Utilities.getChecksum(content),
                                    code: code
                            )
                    )
                    Utilities.putZipEntry(zipStream, "scripts\\" + code + ".groovy", content.bytes)
                }
                Dto.SrcInfoRoot rootInfo = new Dto.SrcInfoRoot(modules: modulesInfo, scripts: scriptsInfo, advImports : advImportsInfo)
                ObjectMapper om = Utilities.getObjectMapper()
                Utilities.putZipEntry(zipStream, "info.json", om.writeValueAsBytes(rootInfo))
            } catch (IOException e) {
                throw new WebApiException.InternalServerError(e.message)
            }
            webApiUtilities.setBodyAsBytes(outputStream.toByteArray(), "application/zip")
    }
}

@SuppressWarnings("unused")
void getSrcInfo(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, new Preferences().assertHttpMethod('POST').assertSuperuser()).process {
        WebApiUtilities webApiUtilities ->
            Dto.SrcRequest body = webApiUtilities.getBodyAsJsonElseThrow(Dto.SrcRequest)
            SrcService srcService = new SrcService()
            Map<String, ScriptModule> modules = srcService.getModules(body.allModules, body.modules)
            Map<String, ru.naumen.metainfo.shared.script.Script> scripts = srcService.getScripts(body.allScripts, body.scripts)
            Map<String, ImportConfigContainer> advImports = srcService.getAdvImports(body.allAdvImports, body.advImports)
            List<Dto.SrcInfo> modulesInfo = []
            List<Dto.SrcInfo> scriptsInfo = []
            List<Dto.SrcInfo> advImportsInfo = []
            advImports.each { code, object ->
                String content = object.getConfigContainer().getConfig()
                advImportsInfo.add(
                        new Dto.SrcInfo(
                                checksum: Utilities.getChecksum(content),
                                code: code
                        )
                )
            }
            modules.each { code, object ->
                ScriptContainer script = object.getScriptElement()
                String content = script.getBody()
                modulesInfo.add(
                        new Dto.SrcInfo(
                                checksum: Utilities.getChecksum(content),
                                code: code
                        )
                )
            }
            scripts.each { code, object ->
                String content = object.getBody()
                scriptsInfo.add(
                        new Dto.SrcInfo(
                                checksum: Utilities.getChecksum(content),
                                code: code
                        )
                )
            }
            Dto.SrcInfoRoot rootInfo = new Dto.SrcInfoRoot(modules: modulesInfo, scripts: scriptsInfo)
            webApiUtilities.setBodyAsJson(rootInfo)
    }
}
