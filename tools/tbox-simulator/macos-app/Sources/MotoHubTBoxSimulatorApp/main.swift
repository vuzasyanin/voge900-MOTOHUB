import Foundation
import SwiftUI
import AppKit
import CoreImage.CIFilterBuiltins
import CoreWLAN
import Darwin

@main
struct MotoHubTBoxSimulatorApp: App {
    @NSApplicationDelegateAdaptor(SimulatorAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup("MOTO-HUB T-Box Simulator") {
            ContentView(model: appDelegate.model)
                .frame(minWidth: 780, minHeight: 700)
                .padding(22)
        }
    }
}

@MainActor
final class SimulatorAppDelegate: NSObject, NSApplicationDelegate {
    let model = SimulatorModel()

    func applicationWillTerminate(_ notification: Notification) {
        model.stop()
    }
}

@MainActor
final class SimulatorModel: ObservableObject {
    struct DisplayGeometry: Equatable {
        let displayWidth: Int
        let displayHeight: Int
        let safeX: Int
        let safeY: Int
        let safeWidth: Int
        let safeHeight: Int
    }

    enum DisplayProfile: String, CaseIterable, Identifiable {
        case automatic = "automatic"
        case landscapeSD = "landscape_sd"
        case landscapeHD = "landscape_hd"
        case portraitSD = "portrait_sd"
        case portraitHD = "portrait_hd"
        case manual = "manual"

        var id: String { rawValue }

        var label: String {
            switch self {
            case .automatic: return "Авто · TFT 800 x 480 / app 800 x 384"
            case .landscapeSD: return "Весь экран · альбом 800 x 480"
            case .landscapeHD: return "Весь экран · альбом 1280 x 720"
            case .portraitSD: return "Весь экран · портрет 720 x 1280"
            case .portraitHD: return "Весь экран · портрет 1080 x 1920"
            case .manual: return "Вручную"
            }
        }

        var geometry: DisplayGeometry? {
            switch self {
            case .automatic:
                return DisplayGeometry(
                    displayWidth: 800,
                    displayHeight: 480,
                    safeX: 0,
                    safeY: 0,
                    safeWidth: 800,
                    safeHeight: 384
                )
            case .landscapeSD:
                return DisplayGeometry(
                    displayWidth: 800,
                    displayHeight: 480,
                    safeX: 0,
                    safeY: 0,
                    safeWidth: 800,
                    safeHeight: 480
                )
            case .landscapeHD:
                return DisplayGeometry(
                    displayWidth: 1280,
                    displayHeight: 720,
                    safeX: 0,
                    safeY: 0,
                    safeWidth: 1280,
                    safeHeight: 720
                )
            case .portraitSD:
                return DisplayGeometry(
                    displayWidth: 720,
                    displayHeight: 1280,
                    safeX: 0,
                    safeY: 0,
                    safeWidth: 720,
                    safeHeight: 1280
                )
            case .portraitHD:
                return DisplayGeometry(
                    displayWidth: 1080,
                    displayHeight: 1920,
                    safeX: 0,
                    safeY: 0,
                    safeWidth: 1080,
                    safeHeight: 1920
                )
            case .manual: return nil
            }
        }
    }

    enum CompatibilityProfile: String, CaseIterable, Identifiable {
        case motohub = "motohub"
        case cfdl16 = "cfdl16"
        case cfdl26Portrait = "cfdl26-portrait"
        case cfdl26Landscape = "cfdl26-landscape"
        case nk800Crcp = "800nk-crcp"
        case nk800Touch = "800nk-touch"
        case model66660742 = "66660742"

        var id: String { rawValue }

        var label: String {
            switch self {
            case .motohub: return "MOTO-HUB Simulator"
            case .cfdl16: return "CFDL16 старый · 37416"
            case .cfdl26Portrait: return "CFDL26 портрет · 37426"
            case .cfdl26Landscape: return "CFDL26 альбом · 37426"
            case .nk800Crcp: return "800NK CRCP · 66660703"
            case .nk800Touch: return "800NK тач · 37426"
            case .model66660742: return "CFDL16 MotoPlay · 66660742"
            }
        }

        var modelId: String {
            switch self {
            case .motohub: return "MOTO-HUB-SIMULATOR"
            case .cfdl16: return "37416"
            case .cfdl26Portrait, .cfdl26Landscape, .nk800Touch: return "37426"
            case .nk800Crcp: return "66660703"
            case .model66660742: return "66660742"
            }
        }

        var qrName: String {
            switch self {
            case .motohub: return "MOTO-HUB Simulator"
            case .cfdl16: return "CFDL16-6GUV"
            case .cfdl26Portrait: return "CFMOTO-805120"
            case .cfdl26Landscape: return "CFMOTO1565"
            case .nk800Crcp, .nk800Touch: return "CFMOTO-800NK"
            case .model66660742: return "CFMOTO-60742"
            }
        }

        var serial: String {
            switch self {
            case .motohub: return "MOTO-HUB-SIM"
            case .cfdl16: return "peTz"
            case .cfdl26Portrait, .cfdl26Landscape: return "0rLs"
            case .nk800Crcp: return "800NK"
            case .nk800Touch: return "800NKT"
            case .model66660742: return "60742"
            }
        }
    }

    private struct CoreConfiguration: Equatable {
        let compatibilityProfile: CompatibilityProfile
        let geometry: DisplayGeometry
        let heartbeatSeconds: Double
    }

    private enum PreferenceKey {
        // Keep the old keys only to migrate existing projection dimensions.
        static let width = "simulator.width"
        static let height = "simulator.height"
        static let displayWidth = "simulator.displayWidth"
        static let displayHeight = "simulator.displayHeight"
        static let safeX = "simulator.safeX"
        static let safeY = "simulator.safeY"
        static let safeWidth = "simulator.safeWidth"
        static let safeHeight = "simulator.safeHeight"
        static let displayProfile = "simulator.displayProfile"
        static let compatibilityProfile = "simulator.compatibilityProfile"
        static let heartbeat = "simulator.heartbeat"
        static let networkSSID = "simulator.networkSSID"
        static let networkPassword = "simulator.networkPassword"
    }

    @Published var displayWidth: String {
        didSet {
            UserDefaults.standard.set(displayWidth, forKey: PreferenceKey.displayWidth)
            markProfileManualIfNeeded()
        }
    }
    @Published var displayHeight: String {
        didSet {
            UserDefaults.standard.set(displayHeight, forKey: PreferenceKey.displayHeight)
            markProfileManualIfNeeded()
        }
    }
    @Published var safeX: String {
        didSet {
            UserDefaults.standard.set(safeX, forKey: PreferenceKey.safeX)
            markProfileManualIfNeeded()
        }
    }
    @Published var safeY: String {
        didSet {
            UserDefaults.standard.set(safeY, forKey: PreferenceKey.safeY)
            markProfileManualIfNeeded()
        }
    }
    @Published var safeWidth: String {
        didSet {
            UserDefaults.standard.set(safeWidth, forKey: PreferenceKey.safeWidth)
            markProfileManualIfNeeded()
        }
    }
    @Published var safeHeight: String {
        didSet {
            UserDefaults.standard.set(safeHeight, forKey: PreferenceKey.safeHeight)
            markProfileManualIfNeeded()
        }
    }
    @Published var displayProfile: DisplayProfile {
        didSet {
            UserDefaults.standard.set(displayProfile.rawValue, forKey: PreferenceKey.displayProfile)
            guard !applyingDisplayProfile, let geometry = displayProfile.geometry else { return }
            applyGeometry(geometry)
        }
    }
    @Published var compatibilityProfile: CompatibilityProfile {
        didSet { UserDefaults.standard.set(compatibilityProfile.rawValue, forKey: PreferenceKey.compatibilityProfile) }
    }
    @Published var heartbeat: String {
        didSet { UserDefaults.standard.set(heartbeat, forKey: PreferenceKey.heartbeat) }
    }
    @Published var networkSSID: String {
        didSet { UserDefaults.standard.set(networkSSID, forKey: PreferenceKey.networkSSID) }
    }
    @Published var networkPassword: String {
        didSet { UserDefaults.standard.set(networkPassword, forKey: PreferenceKey.networkPassword) }
    }
    @Published var controlPort = 0
    @Published var coreStarted = false
    @Published var running = false
    @Published var stopping = false
    @Published var phoneIP = ""
    @Published var frames = 0
    @Published var logLines: [String] = []
    @Published var errorMessage: String?

    private var process: Process?
    private var statusTask: Task<Void, Never>?
    private var applyingDisplayProfile = false
    private var activeCoreConfiguration: CoreConfiguration?
    private var restartRequested = false

    init() {
        let defaults = UserDefaults.standard
        let savedProfile = defaults.string(forKey: PreferenceKey.displayProfile)
            .flatMap(DisplayProfile.init(rawValue:))
        let fallbackGeometry = savedProfile?.geometry ?? DisplayProfile.automatic.geometry!
        let legacySafeWidth = defaults.string(forKey: PreferenceKey.width)
            ?? String(fallbackGeometry.safeWidth)
        let legacySafeHeight = defaults.string(forKey: PreferenceKey.height)
            ?? String(fallbackGeometry.safeHeight)
        let restoredDisplayWidth = defaults.string(forKey: PreferenceKey.displayWidth)
            ?? String(fallbackGeometry.displayWidth)
        let restoredDisplayHeight = defaults.string(forKey: PreferenceKey.displayHeight)
            ?? String(fallbackGeometry.displayHeight)
        let restoredSafeX = defaults.string(forKey: PreferenceKey.safeX)
            ?? String(fallbackGeometry.safeX)
        let restoredSafeY = defaults.string(forKey: PreferenceKey.safeY)
            ?? String(fallbackGeometry.safeY)
        let restoredSafeWidth = defaults.string(forKey: PreferenceKey.safeWidth) ?? legacySafeWidth
        let restoredSafeHeight = defaults.string(forKey: PreferenceKey.safeHeight) ?? legacySafeHeight
        let restoredGeometry = DisplayGeometry(
            displayWidth: Int(restoredDisplayWidth) ?? 0,
            displayHeight: Int(restoredDisplayHeight) ?? 0,
            safeX: Int(restoredSafeX) ?? -1,
            safeY: Int(restoredSafeY) ?? -1,
            safeWidth: Int(restoredSafeWidth) ?? 0,
            safeHeight: Int(restoredSafeHeight) ?? 0
        )
        displayWidth = restoredDisplayWidth
        displayHeight = restoredDisplayHeight
        safeX = restoredSafeX
        safeY = restoredSafeY
        safeWidth = restoredSafeWidth
        safeHeight = restoredSafeHeight
        displayProfile = savedProfile ?? Self.profile(for: restoredGeometry)
        compatibilityProfile = defaults.string(forKey: PreferenceKey.compatibilityProfile)
            .flatMap(CompatibilityProfile.init(rawValue:))
            ?? .motohub
        heartbeat = defaults.string(forKey: PreferenceKey.heartbeat) ?? "1"
        networkSSID = defaults.string(forKey: PreferenceKey.networkSSID)
            ?? CWWiFiClient.shared().interface()?.ssid()
            ?? ""
        networkPassword = defaults.string(forKey: PreferenceKey.networkPassword) ?? ""
    }

    private func markProfileManualIfNeeded() {
        guard !applyingDisplayProfile,
              let profileGeometry = displayProfile.geometry,
              currentGeometry != profileGeometry else { return }
        displayProfile = .manual
    }

    private func applyGeometry(_ geometry: DisplayGeometry) {
        applyingDisplayProfile = true
        displayWidth = String(geometry.displayWidth)
        displayHeight = String(geometry.displayHeight)
        safeX = String(geometry.safeX)
        safeY = String(geometry.safeY)
        safeWidth = String(geometry.safeWidth)
        safeHeight = String(geometry.safeHeight)
        applyingDisplayProfile = false
    }

    private var currentGeometry: DisplayGeometry? {
        guard let displayWidth = Int(displayWidth),
              let displayHeight = Int(displayHeight),
              let safeX = Int(safeX),
              let safeY = Int(safeY),
              let safeWidth = Int(safeWidth),
              let safeHeight = Int(safeHeight) else { return nil }
        return DisplayGeometry(
            displayWidth: displayWidth,
            displayHeight: displayHeight,
            safeX: safeX,
            safeY: safeY,
            safeWidth: safeWidth,
            safeHeight: safeHeight
        )
    }

    private var currentCoreConfiguration: CoreConfiguration? {
        guard let geometry = currentGeometry,
              let heartbeatSeconds = Double(heartbeat), heartbeatSeconds > 0 else { return nil }
        return CoreConfiguration(
            compatibilityProfile: compatibilityProfile,
            geometry: geometry,
            heartbeatSeconds: heartbeatSeconds
        )
    }

    var hasPendingCoreConfiguration: Bool {
        guard coreStarted, let activeCoreConfiguration else { return false }
        return currentCoreConfiguration != activeCoreConfiguration
    }

    private static func profile(for geometry: DisplayGeometry) -> DisplayProfile {
        DisplayProfile.allCases.first { $0.geometry == geometry } ?? .manual
    }

    var controlURL: URL? {
        guard controlPort > 0 else { return nil }
        return URL(string: "http://127.0.0.1:\(controlPort)")
    }

    var pairingPayload: String {
        var components = URLComponents(string: "https://carbit.com/tbox")!
        components.queryItems = [
            URLQueryItem(name: "ssid", value: networkSSID),
            URLQueryItem(name: "pwd", value: networkPassword),
            URLQueryItem(name: "auth", value: "WPA2"),
            URLQueryItem(name: "name", value: compatibilityProfile.qrName),
            URLQueryItem(name: "modelid", value: compatibilityProfile.modelId),
            URLQueryItem(name: "sn", value: compatibilityProfile.serial),
            URLQueryItem(name: "action", value: "9")
        ]
        return components.url?.absoluteString ?? ""
    }

    func copyPairingPayload() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(pairingPayload, forType: .string)
    }

    func start() {
        guard process == nil, !stopping else { return }
        guard let geometry = currentGeometry,
              geometry.displayWidth > 15,
              geometry.displayHeight > 15,
              geometry.safeX >= 0,
              geometry.safeY >= 0,
              geometry.safeWidth > 15,
              geometry.safeHeight > 15 else {
            errorMessage = "Неверные размеры TFT и зоны приложения."
            return
        }
        guard geometry.safeX + geometry.safeWidth <= geometry.displayWidth,
              geometry.safeY + geometry.safeHeight <= geometry.displayHeight else {
            errorMessage = "Зона приложения должна целиком входить в TFT."
            return
        }
        let playerPath = ffplayPath
        guard playerPath != "ffplay" else {
            errorMessage = "ffplay не найден. Установите FFmpeg или проверьте /opt/homebrew/bin/ffplay."
            return
        }
        guard let heartbeatValue = Double(heartbeat), heartbeatValue > 0 else {
            errorMessage = "Интервал heartbeat должен быть больше нуля."
            return
        }
        let configuration = CoreConfiguration(
            compatibilityProfile: compatibilityProfile,
            geometry: geometry,
            heartbeatSeconds: heartbeatValue
        )

        let process = Process()
        process.executableURL = coreURL
        process.arguments = [
            "-profile", configuration.compatibilityProfile.rawValue,
            "-display-width", String(configuration.geometry.displayWidth),
            "-display-height", String(configuration.geometry.displayHeight),
            "-safe-x", String(configuration.geometry.safeX),
            "-safe-y", String(configuration.geometry.safeY),
            "-width", String(configuration.geometry.safeWidth),
            "-height", String(configuration.geometry.safeHeight),
            "-heartbeat", "\(configuration.heartbeatSeconds)s",
            "-ec-port", "0",
            "-control-port", "0",
            "-player", playerPath
        ]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        process.terminationHandler = { [weak self] process in
            let status = process.terminationStatus
            guard let model = self else { return }
            Task { @MainActor in
                model.handleCoreTermination(status: status)
            }
        }
        do {
            try process.run()
        } catch {
            errorMessage = "Не удалось запустить ядро: \(error.localizedDescription)"
            return
        }
        self.process = process
        self.activeCoreConfiguration = configuration
        self.coreStarted = true
        self.controlPort = 0
        logLines.removeAll()
        errorMessage = nil
        readOutput(pipe)
        statusTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refreshStatus()
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    func stop() {
        restartRequested = false
        statusTask?.cancel()
        statusTask = nil
        stopping = true
        if let proc = process {
            proc.terminate()  // SIGTERM — graceful shutdown
            // Security net: if SIGTERM doesn't kill it within 1.5s,
            // force SIGKILL. This handles stuck ffplay or hung shutdown.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                if proc.isRunning {
                    kill(proc.processIdentifier, SIGKILL)
                }
                self?.stopping = false
            }
        } else {
            // No process to stop — clear guard immediately
            stopping = false
        }
        process = nil
        activeCoreConfiguration = nil
        coreStarted = false
        running = false
        phoneIP = ""
        controlPort = 0
    }

    func restartToApplyCoreConfiguration() {
        guard coreStarted, hasPendingCoreConfiguration, !restartRequested else { return }
        guard let proc = process else {
            start()
            return
        }
        restartRequested = true
        statusTask?.cancel()
        statusTask = nil
        running = false
        phoneIP = ""
        logLines.append("Настройки изменены: перезапуск симулятора, чтобы применить TFT и зону.")
        proc.terminate()
        // Force kill after 1.5s if graceful shutdown didn't work.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            if proc.isRunning {
                kill(proc.processIdentifier, SIGKILL)
            }
        }
    }

    func sendGesture(_ path: String) {
        Task { await post(path: path) }
    }

    func sendHandlebar(_ gesture: String) {
        Task { await post(path: "/handlebar", body: ["gesture": gesture]) }
    }

    func sendTap() {
        guard let width = Int(safeWidth), let height = Int(safeHeight) else { return }
        let x = width / 2
        let y = height / 2
        Task {
            await post(path: "/touch", body: [
                "action": "down", "pointerId": 0, "x": x, "y": y
            ])
            await post(path: "/touch", body: [
                "action": "up", "pointerId": 0, "x": x, "y": y
            ])
        }
    }

    private var coreURL: URL {
        let bundlePath = Bundle.main.bundleURL
        let bundledCore = bundlePath.appendingPathComponent("Contents/MacOS/tbox-simulator-core")
        if FileManager.default.isExecutableFile(atPath: bundledCore.path) { return bundledCore }
        let executableDirectory = bundlePath.deletingLastPathComponent()
        let siblingCore = executableDirectory.appendingPathComponent("tbox-simulator-core")
        if FileManager.default.isExecutableFile(atPath: siblingCore.path) { return siblingCore }
        return URL(fileURLWithPath: "/usr/local/bin/tbox-simulator-core")
    }

    private var ffplayPath: String {
        let candidates = ["/opt/homebrew/bin/ffplay", "/usr/local/bin/ffplay", "/usr/bin/ffplay"]
        return candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) ?? "ffplay"
    }

    var sessionLabel: String {
        if running { return "Телефон в сети: \(phoneIP)" }
        if coreStarted { return "Ядро запущено: ждём MOTO-HUB" }
        return "Симулятор стоп"
    }

    var geometryLabel: String {
        guard let geometry = currentGeometry else { return "Геометрия неверна" }
        return "\(compatibilityProfile.label) · TFT \(geometry.displayWidth) x \(geometry.displayHeight) · зона \(geometry.safeWidth) x \(geometry.safeHeight) @(\(geometry.safeX), \(geometry.safeY))"
    }

    private func readOutput(_ pipe: Pipe) {
        // availableData blocks until the core writes or exits. Keep it off the
        // SwiftUI main actor or the window becomes unresponsive immediately.
        DispatchQueue.global(qos: .utility).async { [weak self, pipe] in
            let handle = pipe.fileHandleForReading
            while true {
                let data = handle.availableData
                if data.isEmpty { break }
                let text = String(decoding: data, as: UTF8.self)
                let lines = text.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
                DispatchQueue.main.async { [weak self] in
                    self?.consumeLogLines(lines)
                }
            }
        }
    }

    private func refreshStatus() async {
        guard let controlURL else { return }
        do {
            let (data, _) = try await URLSession.shared.data(from: controlURL.appendingPathComponent("status"))
            let response = try JSONDecoder().decode(StatusResponse.self, from: data)
            await MainActor.run {
                running = response.running
                phoneIP = response.phoneIp ?? ""
                frames = response.frames
            }
        } catch {
            await MainActor.run { running = false }
        }
    }

    private func post(path: String, body: [String: Any]? = nil) async {
        guard let controlURL else {
            await MainActor.run { errorMessage = "Ядро ещё не открыло порт управления." }
            return
        }
        var request = URLRequest(url: controlURL.appendingPathComponent(path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))))
        request.httpMethod = "POST"
        if let body {
            request.httpBody = try? JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        do {
            _ = try await URLSession.shared.data(for: request)
        } catch {
            await MainActor.run { errorMessage = "Сбой команды: \(error.localizedDescription)" }
        }
    }

    private func handleCoreTermination(status: Int32) {
        let shouldRestart = restartRequested
        restartRequested = false
        stopping = false
        // If stop() already cleared these, re-clearing is harmless.
        process = nil
        activeCoreConfiguration = nil
        coreStarted = false
        running = false
        phoneIP = ""
        controlPort = 0
        logLines.append("Ядро завершено, код \(status).")
        if shouldRestart && !coreStarted {
            logLines.append("Перезапуск ядра с новой геометрией.")
            start()
        }
    }

    private func consumeLogLines(_ lines: [String]) {
        lines.forEach(consumeLogLine)
    }

    private func consumeLogLine(_ line: String) {
        logLines.append(line)
        logLines = Array(logLines.suffix(160))
        let marker = "control http://127.0.0.1:"
        guard let start = line.range(of: marker)?.upperBound else { return }
        let portText = line[start...].prefix { $0.isNumber }
        if let port = Int(portText) { controlPort = port }
    }
}

private struct StatusResponse: Decodable {
    let running: Bool
    let phoneIp: String?
    let frames: Int
}

private extension SimulatorModel {
    var processButtonTitle: String { coreStarted ? "Стоп" : "Старт" }
    var processButtonColor: Color { coreStarted ? .red : Color(red: 0.18, green: 0.85, blue: 0.51) }
    var statusIcon: String {
        if running { return "antenna.radiowaves.left.and.right" }
        if coreStarted { return "clock" }
        return "pause.circle"
    }
    var statusColor: Color {
        if running { return Color(red: 0.18, green: 0.85, blue: 0.51) }
        if coreStarted { return .orange }
        return .secondary
    }
}

struct ContentView: View {
    @ObservedObject var model: SimulatorModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // ── Header ──
                headerSection

                // ── Settings ──
                settingsSection

                // ── QR Pairing ──
                qrSection

                // ── Status ──
                statusSection

                // ── Input controls ──
                inputSection

                // ── Log ──
                logSection
            }
            .padding(22)
        }
        .frame(minWidth: 720, minHeight: 600)
        .background(Color(NSColor.windowBackgroundColor))
        .alert("Ошибка", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
    }

    // MARK: - Header

    private var headerSection: some View {
        HStack(spacing: 16) {
            // App icon placeholder
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color(red: 0.07, green: 0.10, blue: 0.11))
                    .frame(width: 52, height: 52)
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(Color(red: 0.18, green: 0.85, blue: 0.51).opacity(0.3), lineWidth: 1)
                    )
                Image(systemName: "cpu")
                    .font(.title2)
                    .foregroundColor(Color(red: 0.18, green: 0.85, blue: 0.51))
            }

            VStack(alignment: .leading, spacing: 2) {
                Text("MOTO-HUB T-Box Simulator")
                    .font(.title2.weight(.semibold))
                Text("Эмуляция T-Box CFMOTO для тестов MOTO-HUB без мото")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            // Status dot
            HStack(spacing: 8) {
                Circle()
                    .fill(model.statusColor)
                    .frame(width: 10, height: 10)
                    .animation(.easeInOut(duration: 0.3), value: model.running)
                Text(model.running ? "Связь" : model.coreStarted ? "Ожидание" : "Стоп")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }

            // Start/Stop button
            Button(action: { model.coreStarted ? model.stop() : model.start() }) {
                Label(
                    model.processButtonTitle,
                    systemImage: model.coreStarted ? "stop.fill" : "play.fill"
                )
                .font(.headline)
                .frame(width: 90)
            }
            .buttonStyle(.borderedProminent)
            .tint(model.processButtonColor)
            .keyboardShortcut(.return)
            .disabled(model.stopping)
        }
    }

    // MARK: - Settings

    private var settingsSection: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 14) {
                Label("Настройка T-Box", systemImage: "gearshape.2")
                    .font(.headline)

                // Profile row
                HStack(spacing: 20) {
                    LabeledContent("Профиль T-Box") {
                        Picker("", selection: $model.compatibilityProfile) {
                            ForEach(SimulatorModel.CompatibilityProfile.allCases) { profile in
                                Text(profile.label).tag(profile)
                            }
                        }
                        .labelsHidden()
                        .frame(width: 240)
                    }
                    LabeledContent("Профиль экрана") {
                        Picker("", selection: $model.displayProfile) {
                            ForEach(SimulatorModel.DisplayProfile.allCases) { profile in
                                Text(profile.label).tag(profile)
                            }
                        }
                        .labelsHidden()
                        .frame(width: 240)
                    }
                    LabeledContent("Heartbeat") {
                        HStack(spacing: 4) {
                            TextField("1", text: $model.heartbeat)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 60)
                                .multilineTextAlignment(.trailing)
                            Text("s").foregroundStyle(.secondary)
                        }
                    }
                }

                Divider()

                // Geometry fields
                Label("Геометрия экрана", systemImage: "rectangle.split.sidebar")
                    .font(.headline)

                VStack(spacing: 10) {
                    // TFT physical
                    HStack(spacing: 12) {
                        Text("TFT экран")
                            .frame(width: 80, alignment: .leading)
                            .foregroundStyle(.secondary)
                        LabeledContent("Ширина") {
                            TextField("800", text: $model.displayWidth)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 80)
                                .multilineTextAlignment(.trailing)
                        }
                        LabeledContent("Высота") {
                            TextField("480", text: $model.displayHeight)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 80)
                                .multilineTextAlignment(.trailing)
                        }
                        Spacer()
                    }

                    // App area
                    HStack(spacing: 12) {
                        Text("Зона app")
                            .frame(width: 80, alignment: .leading)
                            .foregroundStyle(.secondary)
                        LabeledContent("X") {
                            TextField("0", text: $model.safeX)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 60)
                                .multilineTextAlignment(.trailing)
                        }
                        LabeledContent("Y") {
                            TextField("0", text: $model.safeY)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 60)
                                .multilineTextAlignment(.trailing)
                        }
                        LabeledContent("W") {
                            TextField("800", text: $model.safeWidth)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 60)
                                .multilineTextAlignment(.trailing)
                        }
                        LabeledContent("H") {
                            TextField("384", text: $model.safeHeight)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 60)
                                .multilineTextAlignment(.trailing)
                        }
                        Spacer()
                    }
                }
                .font(.callout)

                if model.hasPendingCoreConfiguration {
                    HStack(spacing: 10) {
                        Label(
                            "Экран изменён: активное ядро ещё работает со старой геометрией.",
                            systemImage: "exclamationmark.triangle.fill"
                        )
                        .font(.caption)
                        .foregroundStyle(.orange)
                        Spacer()
                        Button("Перезапуск") {
                            model.restartToApplyCoreConfiguration()
                        }
                        .controlSize(.small)
                    }
                    .padding(8)
                    .background(.orange.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
                }

                Text("Превью — весь TFT; тёмные зоны вокруг области приложения — место под панель мото.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(12)
        }
    }

    // MARK: - QR

    private var qrSection: some View {
        GroupBox {
            HStack(spacing: 18) {
                QRCodeView(value: model.pairingPayload)
                    .frame(width: 130, height: 130)

                VStack(alignment: .leading, spacing: 10) {
                    Label("QR-пара", systemImage: "qrcode")
                        .font(.headline)

                    Text("Отсканируйте QR в MOTO-HUB, чтобы подключиться к симулятору.")
                        .font(.callout)
                        .foregroundStyle(.secondary)

                    HStack(spacing: 12) {
                        LabeledContent("SSID") {
                            TextField("SSID домашней Wi-Fi", text: $model.networkSSID)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 200)
                        }
                        LabeledContent("Пароль") {
                            SecureField("Пароль Wi-Fi", text: $model.networkPassword)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 180)
                        }
                    }
                    .font(.callout)

                    HStack(spacing: 8) {
                        Button(action: model.copyPairingPayload) {
                            Label("Копия QR", systemImage: "doc.on.doc")
                        }
                        .controlSize(.small)

                        Text(model.pairingPayload)
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                            .textSelection(.enabled)
                    }
                }
            }
            .padding(12)
        }
    }

    // MARK: - Status

    private var statusSection: some View {
        GroupBox {
            HStack(spacing: 24) {
                Label(model.sessionLabel, systemImage: model.statusIcon)
                    .foregroundColor(model.statusColor)
                    .font(.callout)
                Label("\(model.frames) frame", systemImage: "film")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Spacer()
                if model.coreStarted {
                    Label("Превью TFT в ffplay", systemImage: "display")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(12)
            .background(model.coreStarted ? Color(red: 0.18, green: 0.85, blue: 0.51).opacity(0.06) : Color.clear, in: RoundedRectangle(cornerRadius: 6))

            if !model.geometryLabel.isEmpty {
                Text(model.geometryLabel)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.tertiary)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
            }
        }
    }

    // MARK: - Input Controls

    private var inputSection: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 14) {
                Label("Эмуляция ввода", systemImage: "hand.point.up")
                    .font(.headline)

                VStack(alignment: .leading, spacing: 10) {
                    Text("Тач / жесты")
                        .font(.subheadline.weight(.medium))
                    HStack(spacing: 8) {
                        Button("Тап в центре") { model.sendTap() }
                            .controlSize(.small)
                        Button("Pinch") { model.sendGesture("/gesture/pinch") }
                            .controlSize(.small)
                        Button("Поворот") { model.sendGesture("/gesture/rotate") }
                            .controlSize(.small)
                        Spacer()
                        Text("Координаты относительно зоны app")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }

                Divider()

                VStack(alignment: .leading, spacing: 10) {
                    Text("Кнопки руля")
                        .font(.subheadline.weight(.medium))
                    HStack(spacing: 8) {
                        Group {
                            Button("Вверх") { model.sendHandlebar("volumeUp") }
                            Button("Вверх ×2") { model.sendHandlebar("volumeUpDouble") }
                            Button("Вниз") { model.sendHandlebar("volumeDown") }
                            Button("Вниз ×2") { model.sendHandlebar("volumeDownDouble") }
                        }
                        .controlSize(.small)

                        Divider().frame(height: 16)

                        Group {
                            Button("Выбор") { model.sendHandlebar("enter") }
                            Button("Удерж") { model.sendHandlebar("enterLong") }
                            Button("×2") { model.sendHandlebar("enterDouble") }
                        }
                        .controlSize(.small)

                        Divider().frame(height: 16)

                        Group {
                            Button("←") { model.sendHandlebar("trackBack") }
                            Button("← ←") { model.sendHandlebar("trackBackDouble") }
                            Button("→") { model.sendHandlebar("trackForward") }
                            Button("→ →") { model.sendHandlebar("trackForwardDouble") }
                        }
                        .controlSize(.small)

                        Spacer()
                    }
                    Text("Логические жесты, не Bluetooth-события")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(12)
        }
    }

    // MARK: - Log

    private var logSection: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 8) {
                Label("Log", systemImage: "text.alignleft")
                    .font(.headline)

                if model.logLines.isEmpty {
                    Text("Нет логов — запустите симулятор, чтобы увидеть вывод ядра.")
                        .font(.callout)
                        .foregroundStyle(.tertiary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 40)
                } else {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 2) {
                                ForEach(Array(model.logLines.enumerated()), id: \.offset) { index, line in
                                    HStack(spacing: 0) {
                                        Text("\(index + 1)")
                                            .font(.system(.caption2, design: .monospaced))
                                            .foregroundStyle(.tertiary)
                                            .frame(width: 32, alignment: .trailing)
                                            .padding(.trailing, 8)
                                        Text(line)
                                            .font(.system(.caption, design: .monospaced))
                                            .textSelection(.enabled)
                                    }
                                    .id(index)
                                }
                            }
                        }
                        .onChange(of: model.logLines.count) { count in
                            if count > 0 { proxy.scrollTo(count - 1, anchor: .bottom) }
                        }
                    }
                    .frame(minHeight: 140)
                }
            }
            .padding(12)
        }
    }
}

struct QRCodeView: View {
    let value: String

    var body: some View {
        Group {
            if let image = makeImage(value: value) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .padding(8)
                    .background(.white)
            } else {
                Image(systemName: "qrcode")
                    .font(.system(size: 64))
            }
        }
        .frame(width: 150, height: 150)
    }

    private func makeImage(value: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(value.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scale = 8.0
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: 150, height: 150))
    }
}
