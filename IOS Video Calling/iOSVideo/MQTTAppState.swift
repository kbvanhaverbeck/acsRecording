//
//  MQTTAppState.swift
//  iOSVideo
//
//  Created by Kayla VanHaverbeck on 11/5/21.
//

import Combine
import Foundation
import UIKit
import NotificationBannerSwift


enum MQTTAppConnectionState {
    case connected
    case disconnected
    case connecting
    case connectedSubscribed
    case connectedUnSubscribed

    var description: String {
        switch self {
        case .connected:
            return "Connected"
        case .disconnected:
            return "Disconnected"
        case .connecting:
            return "Connecting"
        case .connectedSubscribed:
            return "Subscribed"
        case .connectedUnSubscribed:
            return "Connected Unsubscribed"
        }
    }
    var isConnected: Bool {
        switch self {
        case .connected, .connectedSubscribed, .connectedUnSubscribed:
            return true
        case .disconnected,.connecting:
            return false
        }
    }
    
    var isSubscribed: Bool {
        switch self {
        case .connectedSubscribed:
            return true
        case .disconnected,.connecting, .connected,.connectedUnSubscribed:
            return false
        }
    }
}

final class MQTTAppState: UIViewController, ObservableObject {
    @Published var appConnectionState: MQTTAppConnectionState = .disconnected
    @Published var historyText: String = ""
    private var receivedMessage: String = ""

    func setReceivedMessage(text: String) {
        receivedMessage = text
        historyText = historyText + "\n" + receivedMessage
        
        showAlert(alertText: receivedMessage, alertMessage: "String2")
    }

    func clearData() {
        receivedMessage = ""
        historyText = ""
    }

    func setAppConnectionState(state: MQTTAppConnectionState) {
        appConnectionState = state
        print("setAppConnectionState")
    }
}

extension UIViewController {
//Show a basic alert
   public func showAlert(alertText : String, alertMessage : String) {
       let alertText = "We noticed you left a pet behind, please be advised that extend mode does not permit for pets or minors to be left behind while mode is active."

 /*      let alertDisapperTimeInSeconds = 2.0
       
       let alert = UIAlertController(title: nil, message: alertText, preferredStyle: .alert)
       //action sheet shows it nicely at the bottom - i like that but it needs to be at the top.
       */
       print("showing alert")
       

       let banner = FloatingNotificationBanner(title: nil, subtitle: alertText, subtitleColor: UIColor.darkGray)
       
       //let banner = FloatingNotificationBanner(title: nil, subtitle: alertText, style: .info)
       banner.autoDismiss = false
      // banner.delegate = self
       //banner.backgroundColor = UIColor(red: 236, green: 236, blue: 236, alpha: 1.00)
       banner.backgroundColor = UIColor(red: 235/255, green: 235/255, blue: 235/255, alpha: 1)
       
       
       banner.onTap = {
           MQTTManagerSingle.shared().publishACSDemo(with: "StopRecording")
           banner.dismiss()
       }
       
       banner.show(cornerRadius: 10, shadowBlurRadius: 15 )
       
       
   

           /*if var topController = UIApplication.shared.keyWindow?.rootViewController {
                   while let presentedViewController = topController.presentedViewController {
                       topController = presentedViewController
                   }
                   topController.present(alert, animated: true, completion: nil)
               
                   DispatchQueue.main.asyncAfter(deadline: DispatchTime.now() + alertDisapperTimeInSeconds) {
                     alert.dismiss(animated: true)
                   }
            }*/
  }
}
