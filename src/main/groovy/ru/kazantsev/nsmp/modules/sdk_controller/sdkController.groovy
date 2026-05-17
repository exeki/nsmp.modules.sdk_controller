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

import ru.naumen.core.server.script.modules.storage.ScriptModulesStorageService
import ru.naumen.core.server.script.storage.ScriptStorageService
import ru.naumen.core.server.script.modules.storage.ScriptModule
import ru.naumen.core.server.SpringContext
import ru.naumen.commons.server.utils.MessageDigestUtils
import ru.naumen.core.server.script.modules.storage.ScriptContainer
import ru.naumen.metainfo.shared.script.Script as NScript
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
            return this.add(api.metainfo.getMetaClass(metaClassCode))
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
                if (it == null) return
                process(it)
            }
            someMetaCodeWrapper.getChildren().each {
                if (it == null) return
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

    ImportConfigContainer getAdvImport(String uuid, Boolean throwIfNotFount) {
        def obj = metaStorageService.get('advimport', uuid)
        if (throwIfNotFount && obj == null) throw new WebApiException.BadRequest("AdvImport ${code} not found")
        return obj
    }

    List<ImportConfigContainer> getAllAdvImports() {
        return metaStorageService.get('advimport')
    }

    Map<String, ImportConfigContainer> getAdvImports(Boolean all, List<String> uuids, List<String> excluded, Boolean throwIfNotFount = false) {
        Map<String, ImportConfigContainer> map = [:]
        if (all) getAllAdvImports().each { map.put(it.getUUID(), it) }
        else uuids.each {
            def obj = getAdvImport(it, throwIfNotFount)
            if (obj) map.put(it, obj)
        }
        if (excluded) excluded.each {
            map.remove(it)
        }
        return map
    }

    List<Dto.SrcOption> getAdvImportOptions(String lang = null) {
        return getAllAdvImports().collect { ImportConfigContainer advImport ->
            String title = null
            if (lang != null) title = advImport.title.find { it.lang == lang }?.value
            if (title == null) title = advImport.title.first().value
            return new Dto.SrcOption(code: advImport.uuid, title: title)
        }
    }

    NScript getScript(String code, Boolean throwIfNotFount = false) {
        def obj = scriptStorageService.getScript(code)
        if (throwIfNotFount && obj == null) throw new WebApiException.BadRequest("Script ${code} not found")
        return obj
    }

    List<NScript> getAllScripts() {
        return scriptStorageService.getScripts()
    }

    Map<String, NScript> getScripts(Boolean all, List<String> codes, List<String> excluded, Boolean throwIfNotFount = false) {
        Map<String, NScript> map = [:]
        if (all) getAllScripts().each { map.put(it.code, it) }
        else codes.each {
            def obj = getScript(it, throwIfNotFount)
            if (obj) map.put(it, obj)
        }
        if (excluded) excluded.each { map.remove(it) }
        return map
    }

    List<Dto.SrcOption> getScriptOptions(String lang = null) {
        return getAllScripts().collect { script ->
            String title = null
            if (lang != null) title = script.title.find { it.lang == lang }?.value
            if (title == null) title = script.title.first().value
            return new Dto.SrcOption(code: script.code, title: title)
        }
    }

    ScriptModule getModule(String code, Boolean throwIfNotFount = false) {
        def obj = scriptModulesStorageService.getModule(code).orElse(null)
        if (throwIfNotFount && obj == null) throw new WebApiException.BadRequest("Module ${code} not found")
        return obj
    }

    List<ScriptModule> getAllModules() {
        return scriptModulesStorageService.getUserModules()
    }

    Map<String, ScriptModule> getModules(Boolean all, List<String> codes, List<String> excluded, Boolean throwIfNotFount) {
        Map<String, ScriptModule> map = [:]
        if (all) getAllModules().each { map.put(it.code, it) }
        else codes.each {
            def obj = getModule(it, throwIfNotFount)
            if (obj) map.put(it, obj)
        }
        if (excluded) excluded.each { map.remove(it) }
        return map
    }

    List<Dto.SrcOption> getModuleOptions() {
        return getAllModules().collect { module ->
            return new Dto.SrcOption(code: module.code, title: module.code)
        }
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

    static class SrcRequestWithExclusion extends SrcRequest {
        List<String> modulesExcluded
        List<String> scriptsExcluded
        List<String> advImportsExcluded
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

    static class SrcOption {
        String title
        String code
    }

    static class SrcOptionsContainer {
        List<SrcOption> options = []
        String lang
    }

    static class AdminLog {
        String categoryName
        String category
        Date actionDate
        String authorLogin
        String description
        String uuid

        static AdminLog fromObject(ISDtObject obj) {
            return new AdminLog(
                    categoryName: obj.categoryName,
                    category: obj.category,
                    actionDate: obj.actionDate as Date ,
                    authorLogin: obj.authorLogin,
                    description: obj.description,
                    uuid: obj.UUID
            )
        }
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
            Dto.SrcRequest body = webApiUtilities.getBodyAsJsonElseThrow(Dto.SrcRequestWithExclusion)
            SrcService srcService = new SrcService()
            Map<String, ScriptModule> modules = srcService.getModules(body.allModules, body.modules, body.modulesExcluded, true)
            Map<String, NScript> scripts = srcService.getScripts(body.allScripts, body.scripts, body.scriptsExcluded, true)
            Map<String, ImportConfigContainer> advImports = srcService.getAdvImports(body.allAdvImports, body.advImports, body.advImportsExcluded, true)
            List<Dto.SrcInfo> modulesInfo = []
            List<Dto.SrcInfo> scriptsInfo = []
            List<Dto.SrcInfo> advImportsInfo = []
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
            try (ZipOutputStream zipStream = new ZipOutputStream(outputStream)) {
                advImports.each { code, object ->
                    String content = object.getConfigContainer().getConfig()
                    advImportsInfo.add(
                            new Dto.SrcInfo(
                                    checksum: Utilities.getChecksum(content),
                                    code: code
                            )
                    )
                    Utilities.putZipEntry(zipStream, "advImports\\" + code + ".xml", content.bytes)
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
                Dto.SrcInfoRoot rootInfo = new Dto.SrcInfoRoot(modules: modulesInfo, scripts: scriptsInfo, advImports: advImportsInfo)
                ObjectMapper om = Utilities.getObjectMapper()
                Utilities.putZipEntry(zipStream, "info.json", om.writeValueAsBytes(rootInfo))
            } catch (IOException e) {
                throw new WebApiException.InternalServerError(e.message)
            }
            webApiUtilities.setBodyAsBytes(outputStream.toByteArray(), "application/zip")
    }
}

@SuppressWarnings(["unused", 'GrMethodMayBeStatic'])
void getSrcInfo(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, prefs.copy().assertHttpMethod('POST')).process {
        WebApiUtilities webApiUtilities ->
            Dto.SrcRequest body = webApiUtilities.getBodyAsJsonElseThrow(Dto.SrcRequestWithExclusion)
            SrcService srcService = new SrcService()
            Map<String, ScriptModule> modules = srcService.getModules(body.allModules, body.modules, body.modulesExcluded, false)
            Map<String, NScript> scripts = srcService.getScripts(body.allScripts, body.scripts, body.scriptsExcluded, false)
            Map<String, ImportConfigContainer> advImports = srcService.getAdvImports(body.allAdvImports, body.advImports, body.advImportsExcluded, false)
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
            Dto.SrcInfoRoot rootInfo = new Dto.SrcInfoRoot(modules: modulesInfo, scripts: scriptsInfo, advImports: advImportsInfo)
            webApiUtilities.setBodyAsJson(rootInfo)
    }
}

@SuppressWarnings(["unused", 'GrMethodMayBeStatic'])
void getScriptOptions(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, prefs.copy().assertHttpMethod('GET')).process {
        WebApiUtilities webApiUtilities ->
            String lang = webApiUtilities.getParam("lang").orElse(null)
            webApiUtilities.setBodyAsJson(
                    new Dto.SrcOptionsContainer(
                            lang: lang,
                            options: new SrcService().getScriptOptions(lang)
                    )
            )
    }
}

@SuppressWarnings(["unused", 'GrMethodMayBeStatic'])
void getModuleOptions(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, prefs.copy().assertHttpMethod('GET')).process {
        WebApiUtilities webApiUtilities ->
            webApiUtilities.setBodyAsJson(
                    new Dto.SrcOptionsContainer(
                            lang: null,
                            options: new SrcService().getModuleOptions()
                    )
            )
    }
}

@SuppressWarnings(["unused", 'GrMethodMayBeStatic'])
void getAdvImportOptions(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, prefs.copy().assertHttpMethod('GET')).process {
        WebApiUtilities webApiUtilities ->
            String lang = webApiUtilities.getParam("lang").orElse(null)
            webApiUtilities.setBodyAsJson(
                    new Dto.SrcOptionsContainer(
                            lang: lang,
                            options: new SrcService().getAdvImportOptions(lang)
                    )
            )
    }
}

@SuppressWarnings(["unused", 'GrMethodMayBeStatic'])
void getSrcHistory(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, prefs.copy().assertHttpMethod('GET')).process {
        WebApiUtilities webApiUtilities ->
            String type = webApiUtilities.getParamElseThrow("type")
            String name = webApiUtilities.getParamElseThrow("name")
            Integer page = webApiUtilities.getParamElseThrow("page", Integer)
            Integer pageSize = webApiUtilities.getParamElseThrow("pageSize", Integer)

            webApiUtilities.setBodyAsJson(
                    utils.find(
                            'adminLogRecord',
                            ['category': op.like('%' + type + '%'), ' description ': op.like('%' + name + '%')],
                            sp.limit(pageSize).offset((page - 1) * pageSize)
                    ).collect {
                        Dto.AdminLog.fromObject(it)
                    }
            )
    }
}

