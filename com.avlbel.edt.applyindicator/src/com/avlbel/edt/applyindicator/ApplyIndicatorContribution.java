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
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IViewPart;
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
import com.e1c.g5.dt.applications.ui.IApplicationsUiConstants;
import com.e1c.g5.dt.applications.ui.view.ApplicationLabelProvider;

/**
 * Квадратный индикатор в статусной строке EDT.
 * <p>
 * Текущий проект берётся из активного или открытого представления «Приложения»
 * и его выделения, затем из редактора и {@link SelectionContextProject},
 * затем из единственного открытого проекта рабочей области. Список приложений
 * и факт применения читаются
 * у {@link IApplicationManager} и обновляются по {@link IApplicationListener},
 * а не опросом файлов проекта. Значок пункта меню берётся из
 * {@link ApplicationLabelProvider}: это публичная обёртка над тем же
 * декоратором, который в представлении рисует зелёную галочку и жёлтую звёздочку.
 */
public class ApplyIndicatorContribution extends WorkbenchWindowControlContribution
        implements IApplicationListener {

    private static final Collator COLLATOR = Collator.getInstance();

    private final ISelectionListener selectionListener = (part, selection) -> updateContextProject();
    private final IPartListener partListener = new PartHooks();
    private final IPageListener pageListener = new PageHooks();
    private final IResourceChangeListener resourceListener = this::resourceChanged;
    private final AtomicBoolean supplierClosed = new AtomicBoolean();
    private final Set<String> loggedFailures = new HashSet<>();

    private Composite bar;
    private Canvas canvas;
    private Label caption;
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
        bar = new Composite(parent, SWT.NONE) {
            @Override
            public Point computeSize(int wHint, int hHint, boolean changed) {
                return preferredSize();
            }
        };
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.wrap = false;
        layout.pack = true;
        layout.center = true;
        layout.marginTop = 0;
        layout.marginBottom = 0;
        layout.marginLeft = 3;
        layout.marginRight = 4;
        layout.spacing = 4;
        bar.setLayout(layout);
        bar.setBackgroundMode(SWT.INHERIT_FORCE);
        bar.setBackground(parent.getBackground());
        bar.setForeground(parent.getForeground());
        bar.setFont(parent.getFont());

        canvas = new Canvas(bar, SWT.DOUBLE_BUFFERED);
        canvas.addPaintListener(this::paintIndicator);
        caption = new Label(bar, SWT.NONE);
        caption.setText(Texts.CAPTION);
        caption.setFont(parent.getFont());
        caption.setForeground(parent.getForeground());
        applyTooltip(tooltip);
        bindClick(bar);
        bindClick(canvas);
        bindClick(caption);
        fitSquare();
        bar.addListener(SWT.Resize, event -> fitSquare());

        createColors(parent.getDisplay());
        if (!hooksInstalled) {
            hooksInstalled = true;
            hookWorkbench();
            hookWorkspace();
            scheduleConnect();
            updateContextProject();
        }
        Point size = preferredSize();
        bar.setSize(size);
        return bar;
    }

    private Point preferredSize() {
        Point text = caption == null || caption.isDisposed()
                ? new Point(70, 14)
                : caption.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
        int square = squareSide(text.y);
        return new Point(3 + square + 4 + text.x + 4, text.y);
    }

    private static int squareSide(int textHeight) {
        return Math.max(8, textHeight - 2);
    }

    private void fitSquare() {
        if (bar == null || bar.isDisposed() || canvas == null || canvas.isDisposed() || caption == null
                || caption.isDisposed()) {
            return;
        }
        Point text = caption.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
        int available = bar.getClientArea().height;
        int side = squareSide(text.y);
        if (available > 2) {
            side = Math.min(side, available - 2);
        }
        side = Math.max(6, side);
        RowData data = canvas.getLayoutData() instanceof RowData row ? row : null;
        if (data != null && data.width == side && data.height == side) {
            return;
        }
        canvas.setLayoutData(new RowData(side, side));
        bar.layout(true, true);
    }

    private void bindClick(Control control) {
        control.setCursor(control.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        control.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent event) {
                if (event.button == 1) {
                    showMenu();
                }
            }
        });
    }

    private void applyTooltip(String text) {
        if (bar != null && !bar.isDisposed()) {
            bar.setToolTipText(text);
        }
        if (canvas != null && !canvas.isDisposed()) {
            canvas.setToolTipText(text);
        }
        if (caption != null && !caption.isDisposed()) {
            caption.setToolTipText(text);
        }
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
                projectKnown = false;
                updateContextProject();
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
        projectKnown = false;
        updateContextProject();
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
        if (!projectStructureChanged(event.getDelta())) {
            return;
        }
        asyncUi(() -> {
            projectKnown = false;
            updateContextProject();
        });
    }

    private static boolean projectStructureChanged(IResourceDelta delta) {
        if (delta == null) {
            return false;
        }
        for (IResourceDelta child : delta.getAffectedChildren()) {
            if (!(child.getResource() instanceof IProject)) {
                continue;
            }
            if (child.getKind() == IResourceDelta.ADDED || child.getKind() == IResourceDelta.REMOVED
                    || (child.getFlags() & IResourceDelta.OPEN) != 0) {
                return true;
            }
        }
        return false;
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
        IWorkbenchPage page = current == null ? null : current.getActivePage();
        try {
            if (page != null) {
                IProject fromActiveView = projectFromApplicationsView(page, true);
                if (fromActiveView != null) {
                    return fromActiveView;
                }
                IProject fromSelection = projectFromSelection(page.getSelection());
                if (fromSelection != null) {
                    return fromSelection;
                }
                IEditorPart editor = page.getActiveEditor();
                if (editor != null) {
                    IProject fromEditor = contextProject(editor);
                    if (fromEditor != null) {
                        return fromEditor;
                    }
                }
                IProject fromPage = contextProject(page);
                if (fromPage != null) {
                    return fromPage;
                }
                IProject fromOpenView = projectFromApplicationsView(page, false);
                if (fromOpenView != null) {
                    return fromOpenView;
                }
            }
            return soleWorkspaceProject();
        } catch (RuntimeException ex) {
            Activator.log(IStatus.WARNING, "Не удалось определить текущий проект", ex);
            return soleWorkspaceProject();
        }
    }

    private IProject projectFromApplicationsView(IWorkbenchPage page, boolean activeOnly) {
        if (activeOnly) {
            IWorkbenchPart active = page.getActivePart();
            if (active == null || !IApplicationsUiConstants.VIEW_ID.equals(active.getSite().getId())) {
                return null;
            }
            return projectFromPart(active);
        }
        IViewPart view = page.findView(IApplicationsUiConstants.VIEW_ID);
        return view == null ? null : projectFromPart(view);
    }

    private IProject projectFromPart(IWorkbenchPart part) {
        ISelectionProvider provider = part.getSite().getSelectionProvider();
        return provider == null ? null : projectFromSelection(provider.getSelection());
    }

    private IProject projectFromSelection(ISelection selection) {
        if (selection == null || selection.isEmpty()) {
            return null;
        }
        IProject fromContext = contextProject(selection);
        if (fromContext != null) {
            return fromContext;
        }
        if (!(selection instanceof IStructuredSelection structured)) {
            return null;
        }
        for (Object element : structured.toList()) {
            IProject project = adaptProject(element);
            if (project != null) {
                return project;
            }
        }
        return null;
    }

    private IProject adaptProject(Object element) {
        if (element instanceof IApplication application) {
            return accessible(application.getProject());
        }
        if (element instanceof IProject project) {
            return accessible(project);
        }
        if (element instanceof IResource resource) {
            return accessible(resource.getProject());
        }
        if (element instanceof IAdaptable adaptable) {
            IApplication application = adaptable.getAdapter(IApplication.class);
            if (application != null) {
                return accessible(application.getProject());
            }
            IProject project = adaptable.getAdapter(IProject.class);
            if (project != null) {
                return accessible(project);
            }
            IResource resource = adaptable.getAdapter(IResource.class);
            if (resource != null) {
                return accessible(resource.getProject());
            }
        }
        if (element == null) {
            return null;
        }
        IApplication application = Platform.getAdapterManager().getAdapter(element, IApplication.class);
        if (application != null) {
            return accessible(application.getProject());
        }
        return accessible(Platform.getAdapterManager().getAdapter(element, IProject.class));
    }

    private IProject soleWorkspaceProject() {
        List<IProject> open = openProjects();
        if (open.size() == 1) {
            return open.get(0);
        }
        if (manager == null || open.isEmpty()) {
            return null;
        }
        List<IProject> withApplications = new ArrayList<>();
        for (IProject project : open) {
            try {
                if (!manager.getApplications(project).isEmpty()) {
                    withApplications.add(project);
                }
            } catch (ApplicationException ex) {
                logOnce("list:" + project.getName(),
                        "Не удалось получить приложения проекта " + project.getName(), ex);
            }
        }
        return withApplications.size() == 1 ? withApplications.get(0) : null;
    }

    private static List<IProject> openProjects() {
        List<IProject> open = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isAccessible()) {
                open.add(project);
            }
        }
        return open;
    }

    private static boolean hasAccessibleProject() {
        return !openProjects().isEmpty();
    }

    private static IProject accessible(IProject project) {
        return project != null && project.isAccessible() ? project : null;
    }

    private static IProject contextProject(ISelection selection) {
        try {
            return accessible(SelectionContextProject.getContextProject(selection));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static IProject contextProject(IEditorPart editor) {
        try {
            return accessible(SelectionContextProject.getContextProject(editor));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static IProject contextProject(IWorkbenchPage page) {
        try {
            return accessible(SelectionContextProject.getContextProject(page));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String missingProjectText() {
        return hasAccessibleProject() ? Texts.MANY_PROJECTS : Texts.NO_PROJECT;
    }

    private void reload() {
        if (disposed) {
            return;
        }
        if (manager == null) {
            String text = connectFailed || supplier == null
                    ? Texts.NO_SERVICE
                    : (currentProject == null ? missingProjectText() : Texts.CONNECTING);
            show(ApplyStatus.Level.NEUTRAL, text + Texts.CLICK_HINT, List.of(), List.of());
            return;
        }
        IProject project = currentProject;
        if (project == null || !project.isAccessible()) {
            show(ApplyStatus.Level.NEUTRAL, missingProjectText() + Texts.CLICK_HINT, List.of(), List.of());
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
        applyTooltip(text);
        if (canvas != null && !canvas.isDisposed()) {
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
        Control parent = bar != null && !bar.isDisposed() ? bar : canvas;
        if (disposed || parent == null || parent.isDisposed()) {
            return;
        }
        closeMenu();
        menu = new Menu(parent);
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
        Rectangle area = canvas.getClientArea();
        gc.setBackground(canvas.getBackground());
        gc.fillRectangle(area);
        Color fill = fillFor(level);
        Color border = borderFor(level);
        if (fill == null || fill.isDisposed() || border == null || border.isDisposed()) {
            return;
        }
        int inset = 1;
        int side = Math.min(area.width, area.height) - inset * 2;
        if (side < 4) {
            return;
        }
        int x = area.x + (area.width - side) / 2;
        int y = area.y + (area.height - side) / 2;
        gc.setBackground(fill);
        gc.fillRoundRectangle(x, y, side, side, 2, 2);
        gc.setForeground(border);
        gc.drawRoundRectangle(x, y, side - 1, side - 1, 2, 2);
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
