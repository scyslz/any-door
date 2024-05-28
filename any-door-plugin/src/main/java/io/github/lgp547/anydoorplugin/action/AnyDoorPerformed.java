package io.github.lgp547.anydoorplugin.action;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import io.github.lgp547.anydoorplugin.AnyDoorInfo;
import io.github.lgp547.anydoorplugin.dialog.DataContext;
import io.github.lgp547.anydoorplugin.dialog.MainUI;
import io.github.lgp547.anydoorplugin.dialog.old.TextAreaDialog;
import io.github.lgp547.anydoorplugin.dialog.utils.IdeClassUtil;
import io.github.lgp547.anydoorplugin.dto.ParamCacheDto;
import io.github.lgp547.anydoorplugin.settings.AnyDoorSettingsState;
import io.github.lgp547.anydoorplugin.util.AnyDoorActionUtil;
import io.github.lgp547.anydoorplugin.util.HttpUtil;
import io.github.lgp547.anydoorplugin.util.ImportNewUtil;
import io.github.lgp547.anydoorplugin.util.JsonUtil;
import io.github.lgp547.anydoorplugin.util.NotifierUtil;
import io.github.lgp547.anydoorplugin.util.VmUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 *
 */
public class AnyDoorPerformed {

    public void invoke(Project project, PsiMethod psiMethod, Runnable okAction) {
        PsiClass psiClass = (PsiClass) psiMethod.getParent();
        String className =  convertClassName(project, psiClass, psiMethod);
        String methodName = psiMethod.getName();
        List<String> paramTypeNameList = AnyDoorActionUtil.toParamTypeNameList(psiMethod.getParameterList());


        Optional<AnyDoorSettingsState> anyDoorSettingsStateOpt = AnyDoorSettingsState.getAnyDoorSettingsStateNotExc(project);
        if (anyDoorSettingsStateOpt.isEmpty()) {
            return;
        }

        AnyDoorSettingsState service = anyDoorSettingsStateOpt.get();
        BiConsumer<String, Exception> openExcConsumer = (url, e) -> NotifierUtil.notifyError(project, "call " + url + " error [ " + e.getMessage() + " ]");


        if (paramTypeNameList.isEmpty()) {
            String jsonDtoStr = getJsonDtoStr(className, methodName, paramTypeNameList, "{}", !service.enableAsyncExecute, null);
            openAnyDoor(project, jsonDtoStr, service, openExcConsumer);
            okAction.run();
        } else {
            String cacheKey = AnyDoorActionUtil.genCacheKey(psiClass, psiMethod);
            if (useNewUI(service, project, psiClass, psiMethod, cacheKey, okAction)) {
                return;
            }
            TextAreaDialog dialog = new TextAreaDialog(project, String.format("fill method(%s) param", methodName), psiMethod.getParameterList(), service.getCache(cacheKey), service);
            dialog.setOkAction(() -> {
                okAction.run();
                if (dialog.isChangePid()) {
                    service.pid = dialog.getPid();
                }
                String text = dialog.getText();
                ParamCacheDto paramCacheDto = new ParamCacheDto(dialog.getRunNum(), dialog.getIsConcurrent(), text);
                service.putCache(cacheKey, paramCacheDto);
                // Change to args for the interface type
                if (psiClass.isInterface()) {
                    text = JsonUtil.transformedKey(text, AnyDoorActionUtil.getParamTypeNameTransformer(psiMethod.getParameterList()));
                }
                text = JsonUtil.compressJson(text);
                String jsonDtoStr = getJsonDtoStr(className, methodName, paramTypeNameList, text, !service.enableAsyncExecute, paramCacheDto);
                openAnyDoor(project, jsonDtoStr, service, openExcConsumer);
            });
            dialog.show();
        }
    }

    private static String convertClassName(Project project, PsiClass psiClass, PsiMethod psiMethod) {
        String clazz = psiClass.getQualifiedName();
        try {
            File file = new File(project.getBasePath() + "/.idea/AnyDoorExt.txt");
            if (!file.exists()) {
                file.createNewFile();
            }
            // String clzzz = AnyDoorFileUtil.getTextFileAsString(file);
            Map<String, String> maps = FileUtil.loadLines(file)
                    .stream()
                    .map(e -> e.split("[=:]"))
                    .filter(e -> e.length == 2)
                    .collect(Collectors.toMap(e -> e[0], e -> e[1]));
            return maps.getOrDefault(clazz, clazz);
        } catch (IOException e) {

        }
        return clazz;
    }

    public boolean useNewUI(AnyDoorSettingsState service, Project project, PsiClass psiClass, PsiMethod psiMethod, String cacheKey, Runnable okAction) {
        if (service.enableNewUI) {
            doUseNewUI(service, project, psiClass, psiMethod, cacheKey, okAction, null);
            return true;
        }
        return false;
    }

    public void doUseNewUI(AnyDoorSettingsState service, Project project, PsiClass psiClass, PsiMethod psiMethod, String cacheKey, Runnable okAction, @Nullable Long selectedId) {
        ParamCacheDto cache = service.getCache(cacheKey);
        DataContext instance = DataContext.instance(project);
        MainUI mainUI = new MainUI(psiMethod.getName(), project, instance.getExecuteDataContext(psiClass.getQualifiedName(), IdeClassUtil.getMethodQualifiedName(psiMethod), selectedId, cache.content()));
        mainUI.setOkAction((text) -> {
            okAction.run();
            if (mainUI.isChangePid()) {
                service.pid = mainUI.getPid().longValue();
            }

            ParamCacheDto paramCacheDto = new ParamCacheDto(mainUI.getRunNum(), mainUI.getIsConcurrent(), text);
            service.putCache(cacheKey, paramCacheDto);

            if (psiClass.isInterface()) {
                text = JsonUtil.transformedKey(text, AnyDoorActionUtil.getParamTypeNameTransformer(psiMethod.getParameterList()));
            }
            String jsonDtoStr = getJsonDtoStr(convertClassName(project, psiClass, psiMethod), psiMethod.getName(), AnyDoorActionUtil.toParamTypeNameList(psiMethod.getParameterList()), text, !service.enableAsyncExecute, paramCacheDto);
            openAnyDoor(project, jsonDtoStr, service, (url, e) -> NotifierUtil.notifyError(project, "call " + url + " error [ " + e.getMessage() + " ]"));
        });
        mainUI.show();
    }

    private static void openAnyDoor(Project project, String jsonDtoStr, AnyDoorSettingsState service, BiConsumer<String, Exception> errHandle) {
        if (service.isSelectJavaAttach()) {
            String anyDoorJarPath = ImportNewUtil.getPluginLibPath(AnyDoorInfo.ANY_DOOR_ATTACH_JAR);
            String paramPath = project.getBasePath() + "/.idea/AnyDoorParam.json";
            VmUtil.attachAsync(String.valueOf(service.pid), anyDoorJarPath, jsonDtoStr, paramPath, errHandle);
        } else {
            HttpUtil.postAsyncByJdk(service.mvcAddress + ":" + service.mvcPort + service.mvcWebPathPrefix + "/any_door/run", jsonDtoStr, errHandle);
        }
    }

    @NotNull
    private static String getJsonDtoStr(String className, String methodName, List<String> paramTypeNameList, String content, boolean isSync, @Nullable ParamCacheDto paramCacheDto) {
        JsonObject jsonObjectReq = new JsonObject();
        jsonObjectReq.addProperty("content", content);
        jsonObjectReq.addProperty("methodName", methodName);
        jsonObjectReq.addProperty("className", className);
        jsonObjectReq.addProperty("sync", isSync);
        if (paramCacheDto != null) {
            jsonObjectReq.addProperty("num", paramCacheDto.getRunNum());
            jsonObjectReq.addProperty("concurrent", paramCacheDto.getConcurrent());
        }
        jsonObjectReq.add("parameterTypes", JsonUtil.toJsonArray(paramTypeNameList));

        String anyDoorJarPath = ImportNewUtil.getPluginLibPath(AnyDoorInfo.ANY_DOOR_JAR);
        String dependenceJarFilePath = ImportNewUtil.getPluginLibPath(AnyDoorInfo.ANY_DOOR_ALL_DEPENDENCE_JAR);
        String anyDoorCommonJarPath = ImportNewUtil.getPluginLibPath(AnyDoorInfo.ANY_DOOR_COMMON_JAR);
        jsonObjectReq.add("jarPaths", JsonUtil.toJsonArray(List.of(anyDoorJarPath, dependenceJarFilePath, anyDoorCommonJarPath)));
        return jsonObjectReq.toString();
    }



}