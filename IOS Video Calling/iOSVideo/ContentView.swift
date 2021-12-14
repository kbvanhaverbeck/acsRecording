//
//  ContentView.swift
//  iOSVideo
//
// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.
//

import SwiftUI
import AzureCommunicationCommon
import AzureCommunicationCalling
import AVFoundation
import Foundation
import CocoaMQTT
import Combine


var mqtt: CocoaMQTT!
var myCalleeID : String = ""



struct ContentView: View {
    @State var callee: String = "8:acs:ecf8be17-6440-4a50-acb8-fcdd59ec5881_0000000e-3f7e-364e-9ffb-9c3a0d00b479"
    @State var callClient: CallClient?
    @State var callAgent: CallAgent?
    @State var call: Call?
    @State var deviceManager: DeviceManager?
    @State var localVideoStream:[LocalVideoStream]?
    @State var incomingCall: IncomingCall?
    @State var sendingVideo:Bool = false
    @State var errorMessage:String = "Unknown"

    @State var remoteVideoStreamData:[Int32:RemoteVideoStreamData] = [:]
    @State var previewRenderer:VideoStreamRenderer? = nil
    @State var previewView:RendererView? = nil
    @State var remoteRenderer:VideoStreamRenderer? = nil
    @State var remoteViews:[RendererView] = []
    @State var remoteParticipant: RemoteParticipant?
    @State var remoteVideoSize:String = "Unknown"
    @State var isIncomingCall:Bool = false
    
    @State var userAccessToken: String = ""
    
    @State var callObserver:CallObserver?
    @State var remoteParticipantObserver:RemoteParticipantObserver?
    
    @StateObject var mqttManagerSingle = MQTTManagerSingle.shared()

    @State var topic: String = ""
    @State var message: String = ""

    
    var body: some View {
        NavigationView {
            ZStack{
                Form {
                    Section {
                        TextField("Who would you like to call?", text: $callee)
                        Button(action: startCall) {
                            Text("Start Call")
                        }.disabled(callAgent == nil)
                        Button(action: endCall) {
                            Text("End Call")
                        }.disabled(call == nil)
                        Button(action: toggleLocalVideo) {
                            HStack {
                                Text(sendingVideo ? "Turn Off Video" : "Turn On Video")
                            }
                        }
                    }
                }

                if (isIncomingCall) {
                    HStack() {
                        VStack {
                            Text("Incoming call")
                                .padding(10)
                                .frame(maxWidth: .infinity, alignment: .topLeading)
                        }
                        Button(action: answerIncomingCall) {
                            HStack {
                                Text("Answer")
                            }
                            .frame(width:80)
                            .padding(.vertical, 10)
                            .background(Color(.green))
                        }
                        Button(action: declineIncomingCall) {
                            HStack {
                                Text("Decline")
                            }
                            .frame(width:80)
                            .padding(.vertical, 10)
                            .background(Color(.red))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    .padding(10)
                    .background(Color.gray)
                }
                ZStack{
                    VStack{
                        ForEach(remoteViews, id:\.self) { renderer in
                            ZStack{
                                VStack{
                                    RemoteVideoView(view: renderer)
                                        .frame(width: .infinity, height: .infinity)
                                        .background(Color(.lightGray))
                                }
                            }
                            Button(action: endCall) {
                                Text("End Call")
                            }.disabled(call == nil)
                            Button(action: toggleLocalVideo) {
                                HStack {
                                    Text(sendingVideo ? "Turn Off Video" : "Turn On Video")
                                }
                            }
                        }
                        
                    }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    VStack{
                        if(sendingVideo)
                        {
                            VStack{
                                PreviewVideoStream(view: previewView!)
                                    .frame(width: 135, height: 240)
                                    .background(Color(.lightGray))
                            }
                        }
                    }.frame(maxWidth:.infinity, maxHeight:.infinity,alignment: .bottomTrailing)
                }
            }
        
     .navigationBarTitle("Video Calling Quickstart")
        }.onAppear{
            //initialize MQTT Manager
            
            
            
            mqttManagerSingle.initializeMQTT()
            mqttManagerSingle.connect()
            
            
            generateTokens()
            
            print("is MQTT Subscribed?", mqttManagerSingle.isSubscribed())


            
            let incomingCallHandler = IncomingCallHandler.getOrCreateInstance()
            incomingCallHandler.contentView = self
            var userCredential: CommunicationTokenCredential?
            
                do {
                    print("getting Communication Token Credential")
                    userCredential = try CommunicationTokenCredential(token: userAccessToken)
                } catch {
                    print("ERROR: It was not possible to create user credential.")
                    return
                }
           
            
            AVAudioSession.sharedInstance().requestRecordPermission { (granted) in
                if granted {
                    AVCaptureDevice.requestAccess(for: .video) { (videoGranted) in
                        /* NO OPERATION */
                    }
                }
            }

            self.callClient = CallClient()
            self.callClient?.createCallAgent(userCredential: userCredential!) { (agent, error) in
                if error != nil {
                    print("ERROR: It was not possible to create a call agent.")
                    return
                }

                else {
                    self.callAgent = agent
                    print("Call agent successfully created.")
                    self.callAgent!.delegate = incomingCallHandler
                    self.callClient?.getDeviceManager { (deviceManager, error) in
                        if (error == nil) {
                            print("Got device manager instance")
                            self.deviceManager = deviceManager
                        } else {
                            print("Failed to get device manager instance")
                        }
                    }
                }
            }
        }
        .environmentObject(mqttManagerSingle)
    }
    
    struct AzureToken : Decodable {
        let name : String
        let description : String
    }
    
    func generateTokens()  {
        print("Let's gene asyncrate some tokens")
        let url = URL(string: "http://sdv-access-token-gmsdv.akscluster.sdvgm.com/token")!

        let semaphore = DispatchSemaphore(value: 0)
        
        let task = URLSession.shared.dataTask(with: url) {(data, response, error) in
            guard let data = data else { return }
            print(String(data: data, encoding: .utf8)!)
            
            do {
                
                let tkn: [AzureToken] = try! JSONDecoder().decode([AzureToken].self, from: data)
                
                print("AccessToken: ", tkn[0].description)
                userAccessToken = tkn[0].description
                print("Identity: ", tkn[0].name)
                myCalleeID = tkn[0].name
                
                print("is MQTT Connected?", mqttManagerSingle.connectionStateMessage())
            
               
                semaphore.signal()
            } catch {
                print("ACCESS DATA ERROR: ", error)
            }
        }

        task.resume()
                _ = semaphore.wait(timeout: DispatchTime.distantFuture)
    }
    
    private func subscribe(topic: String) {
        mqttManagerSingle.subscribe(topic: topic)
    }

    private func usubscribe() {
        mqttManagerSingle.unSubscribeFromCurrentTopic()
    }
    
    private func functionFor(state: MQTTAppConnectionState) -> () -> Void {
        print("Connection State Changed")
        
        topic = "connection/acs_demo";
        
            switch state {
            case .connected, .connectedUnSubscribed, .disconnected, .connecting:
                return {
                    
                    subscribe(topic: topic)
                    print("Is MQTT Subscribed? ", mqttManagerSingle.isSubscribed())

                }
            case .connectedSubscribed:
                return { usubscribe() }
            }
        }
    
    func declineIncomingCall(){
        self.incomingCall!.reject { (error) in }
        isIncomingCall = false
    }
    func showIncomingCallBanner(_ incomingCall: IncomingCall?) {
        isIncomingCall = true
        self.incomingCall = incomingCall
    }
    func answerIncomingCall() {
        isIncomingCall = false
        let options = AcceptCallOptions()
        if (self.incomingCall != nil) {
            guard let deviceManager = deviceManager else {
                return
            }
            
            if (self.localVideoStream == nil) {
                self.localVideoStream = [LocalVideoStream]()
            }
            if(sendingVideo)
            {
                let camera = deviceManager.cameras.first
                localVideoStream!.append(LocalVideoStream(camera: camera!))
                let videoOptions = VideoOptions(localVideoStreams: localVideoStream!)
                options.videoOptions = videoOptions
            }
            self.incomingCall!.accept(options: options) { (call, error) in
                setCallAndObersever(call: call, error: error)
            }
        }
    }
    
    func callRemoved(_ call: Call) {
        self.call = nil
        self.incomingCall = nil
        self.remoteRenderer?.dispose()
        for data in remoteVideoStreamData.values {
            data.renderer?.dispose()
        }
        self.previewRenderer?.dispose()
        sendingVideo = false
    }
    
    func toggleLocalVideo() {
        if (call == nil)
        {
            if(!sendingVideo)
            {
                self.callClient = CallClient()
                self.callClient?.getDeviceManager { (deviceManager, error) in
                    if (error == nil) {
                        print("Got device manager instance")
                        self.deviceManager = deviceManager
                    } else {
                        print("Failed to get device manager instance")
                    }
                }
                guard let deviceManager = deviceManager else {
                    return
                }
                let camera = deviceManager.cameras.first
                let scalingMode = ScalingMode.fit
                if (self.localVideoStream == nil) {
                    self.localVideoStream = [LocalVideoStream]()
                }
                localVideoStream!.append(LocalVideoStream(camera: camera!))
                previewRenderer = try! VideoStreamRenderer(localVideoStream: localVideoStream!.first!)
                previewView = try! previewRenderer!.createView(withOptions: CreateViewOptions(scalingMode:scalingMode))
                self.sendingVideo = true
            }
            else{
                self.sendingVideo = false
                self.previewView = nil
                self.previewRenderer!.dispose()
                self.previewRenderer = nil
            }
        }
        else{
            if (sendingVideo) {
                call!.stopVideo(stream: localVideoStream!.first!) { (error) in
                    if (error != nil) {
                        print("cannot stop video")
                    }
                    else {
                        self.sendingVideo = false
                        self.previewView = nil
                        self.previewRenderer!.dispose()
                        self.previewRenderer = nil
                    }
                }
            }
            else {
                guard let deviceManager = deviceManager else {
                    return
                }
                let camera = deviceManager.cameras.first
                let scalingMode = ScalingMode.fit
                if (self.localVideoStream == nil) {
                    self.localVideoStream = [LocalVideoStream]()
                }
                localVideoStream!.append(LocalVideoStream(camera: camera!))
                previewRenderer = try! VideoStreamRenderer(localVideoStream: localVideoStream!.first!)
                previewView = try! previewRenderer!.createView(withOptions: CreateViewOptions(scalingMode:scalingMode))
                call!.startVideo(stream:(localVideoStream?.first)!) { (error) in
                    if (error != nil) {
                        print("cannot start video")
                    }
                    else {
                        self.sendingVideo = true
                    }
                }
            }
        }
    }

    func startCall() {
        let startCallOptions = StartCallOptions()
        if(sendingVideo)
        {
            if (self.localVideoStream == nil) {
                self.localVideoStream = [LocalVideoStream]()
            }
            let videoOptions = VideoOptions(localVideoStreams: localVideoStream!)
            startCallOptions.videoOptions = videoOptions
        }
        let callees:[CommunicationIdentifier] = [CommunicationUserIdentifier(self.callee)]
        self.callAgent?.startCall(participants: callees, options: startCallOptions) { (call, error) in
            setCallAndObersever(call: call, error: error)
        }
    }
    
    func setCallAndObersever(call:Call!, error:Error?) {
        if (error == nil) {
            self.call = call
            self.callObserver = CallObserver(self)
            self.call!.delegate = self.callObserver
            self.remoteParticipantObserver = RemoteParticipantObserver(self)
        } else {
            print("Failed to get call object")
        }
    }

    func endCall() {
        self.call!.hangUp(options: HangUpOptions()) { (error) in
            if (error != nil) {
                print("ERROR: It was not possible to hangup the call.")
            }
        }
        self.previewRenderer?.dispose()
        self.remoteRenderer?.dispose()
        sendingVideo = false
    }
}

public class RemoteVideoStreamData : NSObject, RendererDelegate {
    public func videoStreamRenderer(didFailToStart renderer: VideoStreamRenderer) {
        owner.errorMessage = "Renderer failed to start"
    }
    
    private var owner:ContentView
    let stream:RemoteVideoStream
    var renderer:VideoStreamRenderer? {
        didSet {
            if renderer != nil {
                renderer!.delegate = self
            }
        }
    }
    
    var views:[RendererView] = []
    init(view:ContentView, stream:RemoteVideoStream) {
        owner = view
        self.stream = stream
    }
    
    public func videoStreamRenderer(didRenderFirstFrame renderer: VideoStreamRenderer) {
        let size:StreamSize = renderer.size
        owner.remoteVideoSize = String(size.width) + " X " + String(size.height)
    }
}

public class CallObserver: NSObject, CallDelegate, IncomingCallDelegate {
    private var owner: ContentView
    init(_ view:ContentView) {
            owner = view
    }
        
    public func call(_ call: Call, didChangeState args: PropertyChangedEventArgs) {
        print("show call state: ")
        print(call)
        
        if(call.state == CallState.connected) {
            initialCallParticipant()
        }
    }

    public func call(_ call: Call, didUpdateRemoteParticipant args: ParticipantsUpdatedEventArgs) {
        for participant in args.addedParticipants {
            participant.delegate = owner.remoteParticipantObserver
            for stream in participant.videoStreams {
                if !owner.remoteVideoStreamData.isEmpty {
                    return
                }
                let data:RemoteVideoStreamData = RemoteVideoStreamData(view: owner, stream: stream)
                let scalingMode = ScalingMode.fit
                data.renderer = try! VideoStreamRenderer(remoteVideoStream: stream)
                let view:RendererView = try! data.renderer!.createView(withOptions: CreateViewOptions(scalingMode:scalingMode))
                data.views.append(view)
                self.owner.remoteViews.append(view)
                owner.remoteVideoStreamData[stream.id] = data
            }
            owner.remoteParticipant = participant
        }
    }
    
    public func initialCallParticipant() {
        for participant in owner.call!.remoteParticipants {
            participant.delegate = owner.remoteParticipantObserver
            for stream in participant.videoStreams {
                renderRemoteStream(stream)
            }
            owner.remoteParticipant = participant
        }
    }
    
    public func renderRemoteStream(_ stream: RemoteVideoStream!) {
        if !owner.remoteVideoStreamData.isEmpty {
            return
        }
        let data:RemoteVideoStreamData = RemoteVideoStreamData(view: owner, stream: stream)
        let scalingMode = ScalingMode.fit
        data.renderer = try! VideoStreamRenderer(remoteVideoStream: stream)
        let view:RendererView = try! data.renderer!.createView(withOptions: CreateViewOptions(scalingMode:scalingMode))
        self.owner.remoteViews.append(view)
        owner.remoteVideoStreamData[stream.id] = data
    }
}

public class RemoteParticipantObserver : NSObject, RemoteParticipantDelegate {
    private var owner:ContentView
    init(_ view:ContentView) {
        owner = view
    }

    public func renderRemoteStream(_ stream: RemoteVideoStream!) {
        let data:RemoteVideoStreamData = RemoteVideoStreamData(view: owner, stream: stream)
        let scalingMode = ScalingMode.fit
        data.renderer = try! VideoStreamRenderer(remoteVideoStream: stream)
        let view:RendererView = try! data.renderer!.createView(withOptions: CreateViewOptions(scalingMode:scalingMode))
        self.owner.remoteViews.append(view)
        owner.remoteVideoStreamData[stream.id] = data
    }

    public func remoteParticipant(_ remoteParticipant: RemoteParticipant, didUpdateVideoStreams args: RemoteVideoStreamsEventArgs) {
        for stream in args.addedRemoteVideoStreams {
            renderRemoteStream(stream)
        }
        for stream in args.removedRemoteVideoStreams {
            for data in owner.remoteVideoStreamData.values {
                data.renderer?.dispose()
            }
            owner.remoteViews.removeAll()
        }
    }
}

struct PreviewVideoStream: UIViewRepresentable {
    let view:RendererView
    func makeUIView(context: Context) -> UIView {
        return view
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}

struct RemoteVideoView: UIViewRepresentable {
    let view:RendererView
    func makeUIView(context: Context) -> UIView {
        return view
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}


final class MQTTManagerSingle: ObservableObject {
    private var mqttClient: CocoaMQTT?
    private var identifier: String!
    private var host: String!
    private var topic: String!
    private var username: String!
    private var password: String!

    
    
    public var contentView: ContentView?

    
    @Published var currentAppState = MQTTAppState()
    private var anyCancellable: AnyCancellable?
    // Private Init
    private init() {
        // Workaround to support nested Observables, without this code changes to state is not propagated
        anyCancellable = currentAppState.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }
    }

    // MARK: Shared Instance
    private static let _shared = MQTTManagerSingle()

    // MARK: - Accessors
    class func shared() -> MQTTManagerSingle {
        return _shared
    }

    func initializeMQTT() {
        // If any previous instance exists then clean it
        if mqttClient != nil {
            mqttClient = nil
        }
        self.host = "52.224.139.195"
        self.username = ""
        self.password = ""
        let clientID = ""

        // TODO: Guard
        mqttClient = CocoaMQTT(clientID: clientID, host: host, port: 1883)
        
        print(mqttClient)

        mqttClient?.willMessage = CocoaMQTTWill(topic: "connection/acs_demo", message: "dieout")
        mqttClient?.keepAlive = 60
        mqttClient?.delegate = self
        
        
    }

    func connect() {
        if let success = mqttClient?.connect(), success {
            currentAppState.setAppConnectionState(state: .connecting)
            print("MQTT Connection Successful")
        } else {
            currentAppState.setAppConnectionState(state: .disconnected)
            print("MQTT Connection Failed")
        }
    }

    func subscribe(topic: String) {
        self.topic = topic
        mqttClient?.subscribe(topic, qos: .qos1)
    }

    func publishACSDemo(with message: String) {
        mqttClient?.publish("connection/acs_demo", withString: message, qos: .qos1, retained: false)
    }
    
    func publishPerceptionOutput(with message: String) {
        mqttClient?.publish("connection/acs_demo", withString: message, qos: .qos1, retained: false)
    }

    func disconnect() {
        mqttClient?.disconnect()
    }

    /// Unsubscribe from a topic
    func unSubscribe(topic: String) {
        mqttClient?.unsubscribe(topic)
    }

    /// Unsubscribe from a topic
    func unSubscribeFromCurrentTopic() {
        mqttClient?.unsubscribe(topic)
    }
    
    func currentHost() -> String? {
        return host
    }
    
    func isSubscribed() -> Bool {
       return currentAppState.appConnectionState.isSubscribed
    }
    
    func isConnected() -> Bool {
        return currentAppState.appConnectionState.isConnected
    }
    
    func connectionStateMessage() -> String {
        return currentAppState.appConnectionState.description
    }
}

extension MQTTManagerSingle: CocoaMQTTDelegate {

    
    func mqtt(_ mqtt: CocoaMQTT, didSubscribeTopic topics: [String]) {
        TRACE("topic: \(topics)")
        currentAppState.setAppConnectionState(state: .connectedSubscribed)

            publishACSDemo(with: myCalleeID)
            publishPerceptionOutput(with: "TEST Perception Message")
    }

    func mqtt(_ mqtt: CocoaMQTT, didConnectAck ack: CocoaMQTTConnAck) {
        TRACE("ack: \(ack)")

        if ack == .accept {
            currentAppState.setAppConnectionState(state: .connected)
            subscribe(topic: "connection/acs_demo")
            subscribe(topic: "/sdv_perception_output")

        }
    }

    func mqtt(_ mqtt: CocoaMQTT, didPublishMessage message: CocoaMQTTMessage, id: UInt16) {
        TRACE("message: \(message.string.description), id: \(id)")
    }

    func mqtt(_ mqtt: CocoaMQTT, didPublishAck id: UInt16) {
        TRACE("id: \(id)")
    }

    func mqtt(_ mqtt: CocoaMQTT, didReceiveMessage message: CocoaMQTTMessage, id: UInt16) {
        TRACE("message: \(message.string.description), id: \(id)")
                
        let str = message.string.description
        let data = Data(str.utf8)
        do {
            // make sure this JSON is in the format we expect
                if let json = try JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] {
                    // try to read out a string array
                    print(json)
                    print (json["event"])
                    if let names = json["event"] as? String {
                        print(names)
                    } else {
                        print ("no event in json")
                    }
                    
                    print (json["dog_detected"])
                    if let dog_detected = json["dog_detected"] as? String {
                        if (dog_detected == "True") {
                            currentAppState.setReceivedMessage(text: "Dog Detectd")
                        } else {
                            print("Dog Detected" + dog_detected)
                        }
                    } else {
                        print ("no event in json")
                    }
                    
                    print (json["person_detected"])
                    if let person_detected = json["person_detected"] as? String {
                        if (person_detected == "True") {
                            currentAppState.setReceivedMessage(text: "Person Detectd")
                        } else {
                            print("Person Detected" + person_detected)
                        }
                    } else {
                        print ("no event in json")
                    }
                }
        } catch let error as NSError {
            print(error)
        }
        

        
        
        //currentAppState.setReceivedMessage(text: message.string.description)
    
    }

    func mqtt(_ mqtt: CocoaMQTT, didUnsubscribeTopic topic: String) {
        TRACE("topic: \(topic)")
        currentAppState.setAppConnectionState(state: .connectedUnSubscribed)
        currentAppState.clearData()
    }

    func mqttDidPing(_ mqtt: CocoaMQTT) {
        TRACE()
    }

    func mqttDidReceivePong(_ mqtt: CocoaMQTT) {
        TRACE()
    }

    func mqttDidDisconnect(_ mqtt: CocoaMQTT, withError err: Error?) {
        TRACE("\(err.description)")
        currentAppState.setAppConnectionState(state: .disconnected)
    }
}

extension MQTTManagerSingle {
    func TRACE(_ message: String = "", fun: String = #function) {
        let names = fun.components(separatedBy: ":")
        var prettyName: String
        if names.count == 1 {
            prettyName = names[0]
        } else {
            prettyName = names[1]
        }

        if fun == "mqttDidDisconnect(_:withError:)" {
            prettyName = "didDisconect"
        }

        print("[MQTT TRACE] [\(prettyName)]: \(message)")
    }
}


extension Optional {
    // Unwrap optional value for printing log only
    var description: String {
        if let wraped = self {
            return "\(wraped)"
        }
        return ""
    }
}
