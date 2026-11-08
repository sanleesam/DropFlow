import CryptoKit
import Darwin
import Foundation
import Network

// Engineering fixture only: sends fixed synthetic bytes, not user files.
// No privileged APIs, network switching, internet relay, or production service.
private let serviceType = "_df-link-probe._tcp"
private let chunk = Data((0..<(256 * 1024)).map { UInt8($0 % 251) })
private let chunks = 128
private let byteCount = chunk.count * chunks

private func digest() -> Data {
    var hash = SHA256()
    for _ in 0..<chunks { hash.update(data: chunk) }
    return Data(hash.finalize())
}

private final class Probe {
    let queue = DispatchQueue(label: "com.dropflow.nearby-link-probe")
    let expected = digest()
    let selfTest: Bool
    var listener: NWListener?
    var browser: NWBrowser?
    var inbound: NWConnection?
    var outbound: NWConnection?
    var receiverHash = SHA256()
    var received = 0

    init(selfTest: Bool) { self.selfTest = selfTest }

    func fail(_ message: String) -> Never {
        FileHandle.standardError.write(Data((message + "\n").utf8))
        exit(1)
    }

    func parameters(direct: Bool) -> NWParameters {
        let options = NWProtocolTCP.Options()
        options.noDelay = true
        let params = NWParameters(tls: nil, tcp: options)
        params.includePeerToPeer = direct
        return params
    }

    func listen(name: String?) throws {
        let params = parameters(direct: name != nil)
        if selfTest {
            params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        }
        let listener = try NWListener(using: params, on: .any)
        self.listener = listener
        if let name { listener.service = NWListener.Service(name: name, type: serviceType) }
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                guard let port = listener.port else { self.fail("Missing listener port") }
                if self.selfTest {
                    self.connect(.hostPort(host: "127.0.0.1", port: port), interface: nil)
                } else {
                    print("Listening for one synthetic transfer as \(name ?? "") on port \(port)")
                }
            case .failed(let error): self.fail("Listener failed: \(error)")
            default: break
            }
        }
        listener.newConnectionHandler = { connection in
            guard self.inbound == nil else { connection.cancel(); return }
            self.inbound = connection
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    print("Receiver path: \(String(describing: connection.currentPath))")
                    self.receive(connection)
                case .failed(let error): self.fail("Receive connection failed: \(error)")
                default: break
                }
            }
            connection.start(queue: self.queue)
        }
        listener.start(queue: queue)
    }

    func browse(name: String) {
        let browser = NWBrowser(for: .bonjour(type: serviceType, domain: "local."),
                                using: parameters(direct: true))
        self.browser = browser
        browser.stateUpdateHandler = { state in
            if case .failed(let error) = state { self.fail("Browse failed: \(error)") }
        }
        browser.browseResultsChangedHandler = { results, _ in
            guard self.outbound == nil else { return }
            for result in results {
                guard case let .service(peer, type, domain, scopedInterface) = result.endpoint,
                      peer == name else { continue }
                let interfaces = result.interfaces + [scopedInterface].compactMap { $0 }
                // Reject a same-LAN answer. Bind the connection to the AWDL interface.
                guard let awdl = interfaces.first(where: { $0.name.hasPrefix("awdl") }) else { continue }
                print("Peer \(peer) discovered via \(awdl.name)")
                browser.cancel()
                self.connect(.service(name: peer, type: type, domain: domain, interface: awdl),
                             interface: awdl)
                return
            }
        }
        browser.start(queue: queue)
    }

    func connect(_ endpoint: NWEndpoint, interface: NWInterface?) {
        let params = parameters(direct: interface != nil)
        params.requiredInterface = interface
        let connection = NWConnection(to: endpoint, using: params)
        outbound = connection
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                print("Sender path: \(String(describing: connection.currentPath))")
                self.send(connection, remaining: chunks, started: DispatchTime.now())
            case .failed(let error): self.fail("Send connection failed: \(error)")
            default: break
            }
        }
        connection.start(queue: queue)
    }

    func receive(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1,
                           maximumLength: min(chunk.count, byteCount - received)) { data, _, done, error in
            if let error { self.fail("Receive failed: \(error)") }
            if let data {
                self.receiverHash.update(data: data)
                self.received += data.count
            }
            if self.received == byteCount {
                let hash = Data(self.receiverHash.finalize())
                guard hash == self.expected else { self.fail("Synthetic payload checksum mismatch") }
                connection.send(content: hash, completion: .contentProcessed { error in
                    if let error { self.fail("Acknowledgement failed: \(error)") }
                    print("Verified \(byteCount) bytes")
                    if !self.selfTest { exit(0) }
                })
            } else if done || data == nil {
                self.fail("Peer closed before the full payload arrived")
            } else {
                self.receive(connection)
            }
        }
    }

    func send(_ connection: NWConnection, remaining: Int, started: DispatchTime) {
        if remaining == 0 {
            connection.receive(minimumIncompleteLength: 32, maximumLength: 32) { data, _, _, error in
                if let error { self.fail("Acknowledgement read failed: \(error)") }
                guard data == self.expected else { self.fail("Missing or invalid SHA-256 acknowledgement") }
                let elapsed = Double(DispatchTime.now().uptimeNanoseconds - started.uptimeNanoseconds) / 1e9
                let rate = Double(byteCount) / (1024 * 1024) / elapsed
                print(String(format: "Verified 32 MiB at %.1f MiB/s (%@)", rate,
                             self.selfTest ? "loopback only" : "AWDL probe"))
                exit(0)
            }
            return
        }
        connection.send(content: chunk, completion: .contentProcessed { error in
            if let error { self.fail("Send failed: \(error)") }
            self.send(connection, remaining: remaining - 1, started: started)
        })
    }
}

let args = Array(CommandLine.arguments.dropFirst())
private let probe = Probe(selfTest: args == ["self-test"])
do {
    if probe.selfTest {
        try probe.listen(name: nil)
    } else if args.count == 2, !args[1].isEmpty, args[1].utf8.count <= 48 {
        switch args[0] {
        case "listen": try probe.listen(name: args[1])
        case "send": probe.browse(name: args[1])
        default: probe.fail("Usage: nearby-link-probe self-test | listen NAME | send NAME")
        }
    } else {
        probe.fail("Usage: nearby-link-probe self-test | listen NAME | send NAME")
    }
} catch {
    probe.fail("Setup failed: \(error)")
}
probe.queue.asyncAfter(deadline: .now() + 45) {
    probe.fail("Probe timed out: no verified transfer on the required path")
}
dispatchMain()
