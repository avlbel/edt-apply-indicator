package com.avlbel.edt.applyindicator;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;

import com._1c.g5.v8.dt.platform.services.ui.SelectionContextProject;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceSupplier;
import com._1c.g5.wiring.ServiceUnavailableException;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationEvent;
import com.e1c.g5.dt.applications.IApplicationListener;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.ui.view.ApplicationLabelProvider;

/**
 * Квадратный индикатор в статусной строке EDT.
 * <p>
 * Текущий проект — тот же, что у представления «Приложения»:
 * {@link SelectionContextProject}. Список приложений и факт применения читаются
 * у {@link IApplicationManager} и обновляются по {@link IApplicationListener},
 * а не опросом файлов проекта. Значок пункта меню берётся из
 * {@link ApplicationLabelProvider}: это публичная обёртка над тем же
 * декоратором, который в представлении рисует зелёную галочку и жёлтую звёздочку.
 */
public class ApplyIndicatorContribution extends WorkbenchWindowControlContribution
        implements IApplicationListener {

    private static final int INDICATOR_SIZE = 22;
    private static final int SQUARE_SIZE = 12;
    private static final Collator COLLATOR = Collator.getInstance();

    private final ISelectionListener selectionListener = (part, selection) -> updateContextProject();
    private final IPartListener partListener = new PartHooks();
    private final IPageListener pageListener = new PageHooks();
    private final IResourceChangeListener resourceListener = this::resourceChanged;
    private final AtomicBoolean supplierClosed = new AtomicBoolean();
    private final Set<String> loggedFailures = new HashSet<>();

    private Canvas canvas;
    private Menu menu;
    private IWorkbenchWindow window;
    private Job connectJob;
    private ServiceSupplier<IApplicationManager> supplier;
    private IApplicationManager manager;
    private ApplicationLabelProvider labelProvider;
    private volatile IProject currentProject;
    private volatile List<IApplication> applications = List.of();
    private volatile ApplyStatus.Level level = ApplyStatus.Level.NEUTRAL;
    private String tooltip = Texts.NO_PROJECT + Texts.CLICK_HINT;
    private String iconSignature = "";
    private boolean projectKnown;
    private boolean hooksInstalled;
    private volatile boolean connectFailed;
    private volatile boolean disposed;

    private Color appliedFill;
    private Color appliedBorder;
    private Color pendingFill;
    private Color pendingBorder;
    private Color neutralFill;
    private Color neutralBorder;

    @Override
    protected Control createControl(Composite parent) {
        canvas = new Canvas(parent, SWT.DOUBLE_BUFFERED) {
            @Override
            public Point computeSize(int wHint, int hHint, boolean changed) {
                return new Point(INDICATOR_SIZE, INDICATOR_SIZE);
            }
        };
        canvas.setSize(INDICATOR_SIZE, INDICATOR_SIZE);
        canvas.setToolTipText(tooltip);
        canvas.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        canvas.addPaintListener(this::paintIndicator);
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent event) {
                if (event.button == 1) {
                    showMenu();
                }
            }
        });
        createColors(parent.getDisplay());
        if (!hooksInstalled) {
            hooksInstalled = true;
            hookWorkbench();
            hookWorkspace();
            scheduleConnect();
            updateContextProject();
        }
        return canvas;
    }

    @Override
    public void dispose() {
        disposed = true;
        if (manager != null) {
            try {
                // В публичном API EDT метод назван с тремя l.
                manager.removeAppllicationListener(this);
            } catch (RuntimeException ex) {
                Activator.log(IStatus.WARNING, "Не удалось отписаться от IApplicationManager", ex);
            }
            manager = null;
        }
        unhookWorkbench();
        unhookWorkspace();
        closeMenu();
        disposeLabelProvider();
        boolean jobWillFinish = false;
        if (connectJob != null && !connectJob.cancel() && connectJob.getState() != Job.NONE) {
            jobWillFinish = true;
        }
        if (!jobWillFinish) {
            closeSupplier();
        }
        super.dispose();
        disposeColors();
    }

    @Override
    public void applicationChanged(IApplicationEvent event) {
        if (event == null || event.getApplication() == null) {
            return;
        }
        IApplicationEvent.ApplicationEventType type = event.getEventType();
        if (type != IApplicationEvent.ApplicationEventType.UPDATE_STATE_CHANGED
                && type != IApplicationEvent.ApplicationEventType.LIFECYCLE_STATE_CHANGED) {
            return;
        }
        IProject eventProject = event.getApplication().getProject();
        asyncUi(() -> {
            IProject project = currentProject;
            if (project == null || eventProject == null || project.equals(eventProject)) {
                reload();
            }
        });
    }

    private void scheduleConnect() {
        if (connectJob != null) {
            return;
        }
        try {
            supplier = ServiceAccess.supplier(IApplicationManager.class, Activator.class);
        } catch (RuntimeException ex) {
            Activator.log(IStatus.ERROR, Texts.NO_SERVICE, ex);
            return;
        }
        connectJob = new Job(Texts.JOB) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                resolveManager();
                return Status.OK_STATUS;
            }
        };
        connectJob.setSystem(true);
        connectJob.schedule();
    }

    private void resolveManager() {
        ServiceSupplier<IApplicationManager> current = supplier;
        if (current == null || disposed) {
            closeSupplier();
            return;
        }
        IApplicationManager resolved;
        try {
            resolved = current.get();
        } catch (ServiceUnavailableException ex) {
            connectFailed = true;
            Activator.log(IStatus.ERROR, Texts.NO_SERVICE, ex);
            asyncUi(this::reload);
            return;
        } catch (RuntimeException ex) {
            connectFailed = true;
            Activator.log(IStatus.ERROR, Texts.NO_SERVICE, ex);
            asyncUi(this::reload);
            return;
        }
        Canvas currentCanvas = canvas;
        Display display = currentCanvas == null || currentCanvas.isDisposed() ? null : currentCanvas.getDisplay();
        if (display == null || display.isDisposed()) {
            closeSupplier();
            return;
        }
        display.asyncExec(() -> {
            if (disposed) {
                closeSupplier();
                return;
            }
            attachManager(resolved);
        });
    }

    private void attachManager(IApplicationManager resolved) {
        if (disposed) {
            closeSupplier();
            return;
        }
        if (manager != null || resolved == null) {
            return;
        }
        manager = resolved;
        manager.addAppllicationListener(this);
        reload();
    }

    private void hookWorkbench() {
        window = getWorkbenchWindow();
        if (window == null) {
            return;
        }
        window.getSelectionService().addSelectionListener(selectionListener);
        window.getPartService().addPartListener(partListener);
        window.addPageListener(pageListener);
    }

    private void unhookWorkbench() {
        if (window == null) {
            return;
        }
        try {
            window.getSelectionService().removeSelectionListener(selectionListener);
            window.getPartService().removePartListener(partListener);
            window.removePageListener(pageListener);
        } catch (RuntimeException ex) {
            Activator.log(IStatus.WARNING, "Не удалось отписаться от окна EDT", ex);
        }
        window = null;
    }

    private void hookWorkspace() {
        ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener,
                IResourceChangeEvent.POST_CHANGE);
    }

    private void unhookWorkspace() {
        try {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
        } catch (IllegalStateException ex) {
            // Рабочая область уже закрыта вместе с EDT.
        }
    }

    private void resourceChanged(IResourceChangeEvent event) {
        IProject project = currentProject;
        if (project == null || event.getDelta() == null) {
            return;
        }
        IResourceDelta projectDelta = event.getDelta().findMember(project.getFullPath());
        if (projectDelta == null) {
            return;
        }
        boolean removed = projectDelta.getKind() == IResourceDelta.REMOVED;
        boolean opennessChanged = (projectDelta.getFlags() & IResourceDelta.OPEN) != 0;
        if (removed || opennessChanged) {
            asyncUi(this::updateContextProject);
        }
    }

    private void updateContextProject() {
        if (disposed) {
            return;
        }
        IProject project = resolveProject();
        if (projectKnown && Objects.equals(project, currentProject)) {
            return;
        }
        projectKnown = true;
        currentProject = project;
        reload();
    }

    private IProject resolveProject() {
        IWorkbenchWindow current = window != null ? window : getWorkbenchWindow();
        if (current == null) {
            return null;
        }
        IWorkbenchPage page = current.getActivePage();
        if (page == null) {
            return null;
        }
        try {
            IProject project = null;
            ISelection selection = page.getSelection();
            if (selection != null) {
                project = SelectionContextProject.getContextProject(selection);
            }
            IEditorPart editor = page.getActiveEditor();
            if (project == null && editor != null) {
                project = SelectionContextProject.getContextProject(editor);
            }
            if (project == null) {
                project = SelectionContextProject.getContextProject(page);
            }
            if (project != null && !project.isAccessible()) {
                return null;
            }
            return project;
        } catch (RuntimeException ex) {
            Activator.log(IStatus.WARNING, "Не удалось определить текущий проект", ex);
            return null;
        }
    }

    private void reload() {
        if (disposed) {
            return;
        }
        if (manager == null) {
            String text = connectFailed || supplier == null
                    ? Texts.NO_SERVICE
                    : (currentProject == null ? Texts.NO_PROJECT : Texts.CONNECTING);
            show(ApplyStatus.Level.NEUTRAL, text + Texts.CLICK_HINT, List.of(), List.of());
            return;
        }
        IProject project = currentProject;
        if (project == null || !project.isAccessible()) {
            show(ApplyStatus.Level.NEUTRAL, Texts.NO_PROJECT + Texts.CLICK_HINT, List.of(), List.of());
            return;
        }
        List<IApplication> apps;
        try {
            apps = new ArrayList<>(manager.getApplications(project));
        } catch (ApplicationException ex) {
            logOnce("list:" + project.getName(),
                    "Не удалось получить приложения проекта " + project.getName(), ex);
            show(ApplyStatus.Level.NEUTRAL,
                    String.format(Texts.READ_FAILED, project.getName()) + Texts.CLICK_HINT, List.of(), List.of());
            return;
        }
        apps.removeIf(Objects::isNull);
        apps.sort(Comparator.comparing(ApplyIndicatorContribution::applicationName, COLLATOR));
        List<ApplicationUpdateState> states = new ArrayList<>(apps.size());
        for (IApplication application : apps) {
            states.add(readState(application));
        }
        if (apps.isEmpty()) {
            show(ApplyStatus.Level.NEUTRAL,
                    String.format(Texts.NO_APPS, project.getName()) + Texts.CLICK_HINT, List.of(), List.of());
            return;
        }
        ApplyStatus status = ApplyStatus.of(states);
        show(status.level(), describe(status, project.getName()), apps, states);
    }

    private ApplicationUpdateState readState(IApplication application) {
        try {
            ApplicationUpdateState state = manager.getUpdateState(application);
            return state == null ? ApplicationUpdateState.UNKNOWN : state;
        } catch (ApplicationException ex) {
            String key = application.getId() == null ? applicationName(application) : application.getId();
            logOnce("state:" + key,
                    "Не удалось прочитать состояние применения «" + applicationName(application) + "»", ex);
            return ApplicationUpdateState.UNKNOWN;
        }
    }

    private static String describe(ApplyStatus status, String projectName) {
        String text;
        switch (status.level()) {
            case APPLIED:
                text = String.format(Texts.APPLIED, projectName);
                break;
            case NOT_APPLIED:
                int pending = status.notApplied() + status.updating();
                text = String.format(Texts.NOT_APPLIED, pending, status.total(), projectName);
                if (status.updating() > 0) {
                    text += String.format(Texts.UPDATING, status.updating());
                }
                break;
            case NEUTRAL:
            default:
                text = String.format(Texts.UNKNOWN, status.unknown(), status.total(), projectName);
                break;
        }
        return text + Texts.CLICK_HINT;
    }

    private void show(ApplyStatus.Level next, String text, List<IApplication> apps,
            List<ApplicationUpdateState> states) {
        level = next;
        tooltip = text;
        applications = List.copyOf(apps);
        ensureIcons(apps, states);
        if (canvas != null && !canvas.isDisposed()) {
            canvas.setToolTipText(text);
            canvas.redraw();
        }
    }

    private void ensureIcons(List<IApplication> apps, List<ApplicationUpdateState> states) {
        String signature = iconSignature(apps, states);
        if (signature.equals(iconSignature) && (apps.isEmpty() || labelProvider != null)) {
            return;
        }
        iconSignature = signature;
        closeMenu();
        disposeLabelProvider();
        if (apps.isEmpty() || manager == null) {
            return;
        }
        try {
            labelProvider = new ApplicationLabelProvider(manager);
        } catch (RuntimeException ex) {
            Activator.log(IStatus.WARNING, "Не удалось создать значки приложений", ex);
        }
    }

    private static String iconSignature(List<IApplication> apps, List<ApplicationUpdateState> states) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < apps.size() && i < states.size(); i++) {
            IApplication application = apps.get(i);
            builder.append(application.getId()).append('=').append(states.get(i)).append('\n');
        }
        return builder.toString();
    }

    private void showMenu() {
        if (disposed || canvas == null || canvas.isDisposed()) {
            return;
        }
        closeMenu();
        menu = new Menu(canvas);
        List<IApplication> apps = applications;
        if (apps.isEmpty()) {
            MenuItem item = new MenuItem(menu, SWT.PUSH);
            item.setEnabled(false);
            item.setText(menuLabel(tooltip));
        } else {
            for (IApplication application : apps) {
                MenuItem item = new MenuItem(menu, SWT.PUSH);
                item.setText(menuText(application));
                Image image = icon(application);
                if (image != null) {
                    item.setImage(image);
                }
            }
        }
        menu.setVisible(true);
    }

    private Image icon(IApplication application) {
        if (labelProvider == null) {
            return null;
        }
        try {
            Image image = labelProvider.getImage(application);
            if (image == null || image.isDisposed()) {
                return null;
            }
            return image;
        } catch (RuntimeException ex) {
            Activator.log(IStatus.WARNING, "Не удалось получить значок приложения", ex);
            return null;
        }
    }

    private void paintIndicator(PaintEvent event) {
        if (canvas == null || canvas.isDisposed()) {
            return;
        }
        GC gc = event.gc;
        try {
            gc.setAntialias(SWT.ON);
        } catch (RuntimeException ex) {
            // На части платформ сглаживание просто недоступно.
        }
        Point size = canvas.getSize();
        gc.setBackground(canvas.getBackground());
        gc.fillRectangle(0, 0, size.x, size.y);
        Color fill = fillFor(level);
        Color border = borderFor(level);
        if (fill == null || fill.isDisposed() || border == null || border.isDisposed()) {
            return;
        }
        int x = Math.max(0, (size.x - SQUARE_SIZE) / 2);
        int y = Math.max(0, (size.y - SQUARE_SIZE) / 2);
        gc.setBackground(fill);
        gc.fillRoundRectangle(x, y, SQUARE_SIZE, SQUARE_SIZE, 3, 3);
        gc.setForeground(border);
        gc.drawRoundRectangle(x, y, SQUARE_SIZE - 1, SQUARE_SIZE - 1, 3, 3);
    }

    private void createColors(Display display) {
        appliedFill = new Color(display, 46, 168, 68);
        appliedBorder = new Color(display, 198, 232, 206);
        pendingFill = new Color(display, 214, 168, 32);
        pendingBorder = new Color(display, 255, 226, 140);
        neutralFill = new Color(display, 132, 136, 142);
        neutralBorder = new Color(display, 196, 200, 204);
    }

    private Color fillFor(ApplyStatus.Level value) {
        if (value == ApplyStatus.Level.APPLIED) {
            return appliedFill;
        }
        if (value == ApplyStatus.Level.NOT_APPLIED) {
            return pendingFill;
        }
        return neutralFill;
    }

    private Color borderFor(ApplyStatus.Level value) {
        if (value == ApplyStatus.Level.APPLIED) {
            return appliedBorder;
        }
        if (value == ApplyStatus.Level.NOT_APPLIED) {
            return pendingBorder;
        }
        return neutralBorder;
    }

    private void disposeColors() {
        disposeColor(appliedFill);
        disposeColor(appliedBorder);
        disposeColor(pendingFill);
        disposeColor(pendingBorder);
        disposeColor(neutralFill);
        disposeColor(neutralBorder);
    }

    private static void disposeColor(Color color) {
        if (color != null && !color.isDisposed()) {
            color.dispose();
        }
    }

    private void disposeLabelProvider() {
        if (labelProvider != null) {
            labelProvider.dispose();
            labelProvider = null;
        }
    }

    private void closeMenu() {
        if (menu != null && !menu.isDisposed()) {
            menu.setVisible(false);
            menu.dispose();
        }
        menu = null;
    }

    private void closeSupplier() {
        ServiceSupplier<IApplicationManager> current = supplier;
        if (current != null && supplierClosed.compareAndSet(false, true)) {
            current.close();
        }
    }

    private void asyncUi(Runnable action) {
        if (disposed) {
            return;
        }
        Canvas current = canvas;
        if (current == null || current.isDisposed()) {
            return;
        }
        Display display = current.getDisplay();
        if (display.isDisposed()) {
            return;
        }
        if (Thread.currentThread() == display.getThread()) {
            if (!disposed) {
                action.run();
            }
            return;
        }
        display.asyncExec(() -> {
            if (!disposed && canvas != null && !canvas.isDisposed()) {
                action.run();
            }
        });
    }

    private static String applicationName(IApplication application) {
        String name = application.getName();
        if (name == null || name.isBlank()) {
            String id = application.getId();
            return id == null ? "" : id;
        }
        return name;
    }

    private static String menuText(IApplication application) {
        String text = applicationName(application).replace("&", "&&").replace("\r", " ").replace("\n", " ").trim();
        return text.isEmpty() ? "Приложение" : text;
    }

    private void logOnce(String key, String message, Throwable throwable) {
        if (loggedFailures.add(key)) {
            Activator.log(IStatus.WARNING, message, throwable);
        }
    }

    private static String menuLabel(String text) {
        if (text == null || text.isBlank()) {
            return Texts.MENU_EMPTY;
        }
        String firstLine = text.split("\n", 2)[0].replace("&", "&&");
        return firstLine.isBlank() ? Texts.MENU_EMPTY : firstLine;
    }

    private final class PartHooks implements IPartListener {

        @Override
        public void partActivated(IWorkbenchPart part) {
            updateContextProject();
        }

        @Override
        public void partBroughtToTop(IWorkbenchPart part) {
        }

        @Override
        public void partClosed(IWorkbenchPart part) {
            updateContextProject();
        }

        @Override
        public void partDeactivated(IWorkbenchPart part) {
        }

        @Override
        public void partOpened(IWorkbenchPart part) {
        }
    }

    private final class PageHooks implements IPageListener {

        @Override
        public void pageActivated(IWorkbenchPage page) {
            updateContextProject();
        }

        @Override
        public void pageClosed(IWorkbenchPage page) {
            updateContextProject();
        }

        @Override
        public void pageOpened(IWorkbenchPage page) {
            updateContextProject();
        }
    }
}
