package io.github.lgp547.anydoor.common.util;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class AnyDoorSpringUtil {

    private static List<ApplicationContext> applicationContextList;
    private static ClassLoader springLoader;
    private static Supplier<ApplicationContext[]> applicationContextsSupplier;
    private static Supplier<BeanFactory[]> beanFactorySupplier;

    private static volatile boolean isInit = false;

    public static void initApplicationContexts(Supplier<ApplicationContext[]> applicationContextsSupplier, @Nullable Supplier<BeanFactory[]> beanFactorySupplier) {
        if (!isInit) {
            synchronized (AnyDoorSpringUtil.class) {
                if (!isInit) {
                    AnyDoorSpringUtil.applicationContextsSupplier = applicationContextsSupplier;
                    AnyDoorSpringUtil.beanFactorySupplier = beanFactorySupplier;
                    updateApplicationContextList();
                    setClassLoader();
                    isInit = true;
                }
            }
        }
    }

    /**
     * 由于没有set进去，会从执行线程获取类加载器
     * 当处于懒加载期间，会有获取到 AnyDoorClassloader 的情况，这里兼容处理
     */
    private static void setClassLoader() {
        for (ApplicationContext applicationContext : applicationContextList) {
            if (applicationContext instanceof DefaultResourceLoader) {
                ((DefaultResourceLoader) applicationContext).setClassLoader(applicationContext.getClassLoader());
                ClassLoader defaultClassLoader = getDefaultClassLoader();
                if(defaultClassLoader instanceof  AnyDoorClassloader){
                    ((AnyDoorClassloader) defaultClassLoader).setSpringLoader(applicationContext.getClassLoader());
                    springLoader = applicationContext.getClassLoader();
                }
            }
        }
    }

    public static ClassLoader getSpringLoader() {
        return springLoader;
    }

    public static ClassLoader getDefaultClassLoader() {
        ClassLoader cl = null;
        try {
            cl = Thread.currentThread().getContextClassLoader();
        }
        catch (Throwable ex) {
            // Cannot access thread context ClassLoader - falling back...
        }
        if (cl == null) {
            // No thread context class loader -> use class loader of this class.
            cl = ClassUtils.class.getClassLoader();
            if (cl == null) {
                // getClassLoader() returning null indicates the bootstrap ClassLoader
                try {
                    cl = ClassLoader.getSystemClassLoader();
                }
                catch (Throwable ex) {
                    // Cannot access system ClassLoader - oh well, maybe the caller can live with null...
                }
            }
        }
        return cl;
    }

    private static List<ApplicationContext> sortList(List<ApplicationContext> curApplicationContextList) {
        if (curApplicationContextList.size() > 1) {
            String contextName = System.getProperty("anydoor.spring.core.context.name");
            if (StringUtils.hasLength(contextName)) {
                Optional<ApplicationContext> any = curApplicationContextList.stream().filter(item -> item.getDisplayName().contains(contextName)).findAny();
                if (any.isPresent()) {
                    curApplicationContextList.clear();
                    curApplicationContextList.add(any.get());
                } else {
                    throw new IllegalArgumentException("not found context name: " + contextName);
                }
            } else {
                ArrayList<String> contextNames = new ArrayList<>();
                contextNames.add("WebServerApplicationContext");
                contextNames.add("ReactiveWebApplicationContext");
                contextNames.add("WebApplicationContext");
                contextNames.add("ConfigurableApplicationContext");
                curApplicationContextList.sort(Comparator.comparing(ApplicationContext::getDisplayName, (name1, name2) -> comparingSort(contextNames, name1, name2)));
            }
        }
        return curApplicationContextList;
    }

    private static int comparingSort(List<String> sort, String name1, String name2) {
        for (String s : sort) {
            if (name1.contains(s)) {
                return -1;
            }
            if (name2.contains(s)) {
                return 1;
            }
        }
        return 0;
    }

    private static List<ApplicationContext> updateApplicationContextList() {
        synchronized (AnyDoorSpringUtil.class) {
            ApplicationContext[] array = applicationContextsSupplier.get();
            if (array == null || array.length == 0) {
                AnyDoorSpringUtil.applicationContextList = Collections.emptyList();
            } else {
                AnyDoorSpringUtil.applicationContextList = Arrays.stream(array).collect(Collectors.toList());
            }
            return sortList(AnyDoorSpringUtil.applicationContextList);
        }
    }

    public static <T> T getBean(Class<T> requiredType) throws BeansException {
        synchronized (AnyDoorSpringUtil.class) {
            Objects.requireNonNull(applicationContextList);

            try {
                return doGetBean(requiredType, applicationContextList);
            } catch (Exception exception) {
                try {
                    return doGetBean(requiredType, updateApplicationContextList());
                } catch (Exception e) {
                    T bean = getBeanByBeanFactories(requiredType);
                    if (bean != null) {
                        return bean;
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    private static <T> T getBeanByBeanFactories(Class<T> requiredType) {
        if (beanFactorySupplier == null) {
            return null;
        }
        BeanFactory[] beanFactories = beanFactorySupplier.get();
        for (BeanFactory beanFactory : beanFactories) {
            try {
                return beanFactory.getBean(requiredType);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static <T> T doGetBean(Class<T> requiredType, List<ApplicationContext> applicationContextList1) {
        for (ApplicationContext applicationContext : applicationContextList1) {
            try {
                return applicationContext.getBean(requiredType);
            } catch (BeansException ignored) {
            }
        }
        throw new NoSuchBeanDefinitionException("No bean of type " + requiredType + " is defined");
    }

    public static boolean containsBean(Class<?> requiredType) {
        try {
            getBean(requiredType);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
