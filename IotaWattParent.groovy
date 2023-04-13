/**
 *
 * IoTaWatt Plus
 *
 * Copyright 2023 Ryan Elliott
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * v0.1.0		RLE		Creation
 * v0.1.1   RLE   Added a lot of error handling. Added healthStatus and presence attributes to parent. Added current meter capability to child.
 *
 *
 * A huge thank you to ogiewon for pioneering the IoTaWatt integration and being a huge help with supporting information.
 */

metadata {
    definition (name: "IoTaWatt+ Parent", namespace: "rle.iw+", author: "FriedCheese2006", importUrl: "https://friedcheese2006.gateway.scarf.sh/IotaWattParent.groovy") {
        capability "Initialize"
        capability "Refresh"
        capability "Health Check"
        capability "Presence Sensor"

        attribute "healthStatus", "enum", ["offline", "online","unknown"]
    }
}

preferences {
    input name: "deviceIP", type: "text", title: getFormat("header","IoTaWatt Hub IP/Hostname"), description: getFormat("important","IP or DNS name"), required: true, displayDuringSetup: true
    input name: "pollingInterval", type: "number", title: getFormat("header","How often should attributes be updated?"), description: getFormat("important","In seconds (10-300)"), range: "10..300", defaultValue: 30, displayDuringSetup: true
    input name: "infoOutput", type: "bool", title: getFormat("header","Enable info logging"), defaultValue: true
    input name: "debugOutput", type: "bool", title: getFormat("header","Enable debug logging"), defaultValue: true
    input name: "traceOutput", type: "bool", title: getFormat("header","Enable trace logging"), defaultValue: true
}

def refresh() {
  logDebug "Running refresh"
  handleUpdates()
  getDeviceList()
}

def installed() {
  initialize()
}

def updated() {
  log.info "Updated with $settings"
  unschedule()
  if (debugOutput) runIn(1800,debugLogsOff)
  if (traceOutput) runIn(1800,traceLogsOff)
  initialize()

}

def initialize() {
  log.info "initialize() called"
  if(deviceIP) getDeviceList()
  if(deviceIP) runIn(5,'handleUpdates')
  state.offlineCount = 0
}

def handleUpdates() {
  runIn(pollingInterval,getDeviceList)
  runIn(pollingInterval+5,handleUpdates)
  state.wattDeviceList.each { it ->
    fetchDeviceEnergy(it)
  }
}

def getDeviceList() {
  logDebug "Getting device list"
  def params = [
    uri: "http://${deviceIP}/query?show=series",
    contentType: "application/json",
    requestContentType: "application/json",
    timeout: 5
  ]
  
  try{
    asynchttpGet('parseDeviceList', params)
  } catch (Exception e) {
    log.error "IoTaWatt Server Returned: ${e}"
    setHealthStatusValue("offline")
  }
}

def parseDeviceList(response, data) {
  def wattDeviceList = []

  try {
    def resp = response?.getJson()
    if(resp && resp instanceof Map && resp.containsKey("series")) {
      setHealthStatusValue("online")
      logTrace "$resp"

      resp.series?.each{ it ->
        wattDeviceList += it.name
      }
      logDebug "Device list is $wattDeviceList"
      state.wattDeviceList = wattDeviceList
      childDeviceHandler()
        } else {
          setHealthStatusValue("offline")
          log.warn "Bad response"
          }
  } catch (e) {
    setHealthStatusValue("offline")
    log.warn "Error parsing response: ${e.message}"
  }
}

def childDeviceHandler() {
  wattDeviceList = state.wattDeviceList
  createdChild = state.createdChild ?: wattDeviceList

  logDebug "Old device list is: $createdChild"

  if(wattDeviceList.sort() != createdChild.sort()) {
    log.info "Device list changed. Adding/removing devices as needed."
    }
  wattDeviceList.each{
    logTrace "Found device $it in list"
    def childDevice = getChildDevice("iw+:_${device.id}_${it}")
    if (!childDevice) {
      logDebug "Creating child device"
      addChildDevice("rle.iw+", "IoTaWatt+ Child", "iw+:_${device.id}_${it}", [label: it, isComponent: false])
    }
  }
  createdChild.each{
    if(!wattDeviceList.contains(it)){
      logDebug "$it was renamed or removed. Deleting child"
      deleteChildDevice("iw+:_${device.id}_${it}")
    }
  }  
  state.createdChild = wattDeviceList
}

def fetchDeviceEnergy(wattDevice) {
  def params = [
      uri: "http://${deviceIP}/query?select=[${wattDevice}.wh.d2]&begin=d&end=s&group=all&header=yes",
      contentType: "application/json",
      requestContentType: "application/json",
      timeout: 5
  ]

  try{
    asynchttpGet('parseDeviceEnergy',params)
    } catch (Exception e) {
        log.error "IoTaWatt Server Returned: ${e}"
        setHealthStatusValue("offline")
    }
}

def parseDeviceEnergy(response, data) {
  try {
    def resp = response?.getJson()
    if(resp && resp instanceof Map && resp.containsKey("labels")) {
        setHealthStatusValue("online")
        wattDevice = resp.labels[0].toString()
        wh = resp.data[0][0].toDouble()
        kwh = (wh / 1000)
        def childDevice = getChildDevice("iw+:_${device.id}_${wattDevice}")
        logTrace "Parsing energy response for $childDevice"
        childDevice.sendEvent(name: "energy", value: kwh, units: kWh, descriptionText: "$childDevice.label energy is $kwh kWh.")
        if(infoOutput) childDevice.logInfo "$wattDevice energy use today is $kwh"
        fetchDeviceOther(wattDevice)
    } else {
      setHealthStatusValue("offline")
      log.warn "Bad response"
    }
  } catch (e) {
    setHealthStatusValue("offline")
    log.warn "Error parsing response: ${e.message}"
  }
}

def fetchDeviceOther(wattDevice) {
  def params = [
      uri: "http://${deviceIP}/query?select=[${wattDevice}.watts.d2,${wattDevice}.amps.d2,${wattDevice}.volts.d2,${wattDevice}.hz.d2]&begin=s-10s&end=s&group=all&header=yes",
      contentType: "application/json",
      requestContentType: "application/json",
      timeout: 5
  ]

  try{
    asynchttpGet('parseDeviceOther',params)
    } catch (Exception e) {
      log.error "IoTaWatt Server Returned: ${e}"
      setHealthStatusValue("offline")
      }
}

def parseDeviceOther(response, data) {
  try {
    def resp = response?.getJson()
    if(resp && resp instanceof Map && resp.containsKey("labels")) {
        setHealthStatusValue("online")
        wattDevice = resp.labels[0].toString()
        watts = resp.data[0][0].toDouble()
        amps = resp.data[0][1].toDouble()
        volts = resp.data[0][2].toDouble()
        // hz = resp.data[0][3].toDouble() //Need to figure out how to deal with outputs that combine the frequency value
        def childDevice = getChildDevice("iw+:_${device.id}_${wattDevice}")
        logTrace "Parsing other attributes response for $childDevice"
        childDevice.sendEvent(name: "power", value: watts, units: W, descriptionText: "$childDevice.label energy is ${watts}W.")
        childDevice.sendEvent(name: "amperage", value: amps, units: A, descriptionText: "$childDevice.label current is ${amps}A.")
        childDevice.sendEvent(name: "voltage", value: volts, units: V, descriptionText: "$childDevice.label voltage is ${volts}V.")
        // childDevice.sendEvent(name: "frequency", value: hz, units: "Hz", descriptionText: "$childDevice.label voltage frequency is ${hz}Hz.")
        if(infoOutput) childDevice.logInfo "$wattDevice current power is $watts"
        if(infoOutput) childDevice.logInfo "$wattDevice current amps is $amps"
        if(infoOutput) childDevice.logInfo "$wattDevice current voltage is $volts"
        } else {
          setHealthStatusValue("offline")
          log.warn "Bad response"
          }
  } catch (e) {
    setHealthStatusValue("offline")
    log.warn "Error parsing response: ${e.message}"
  }  
}

def setHealthStatusValue(value) {
  logTrace "Health status is $value"
  if(value == "online") {
    sendEvent(name: "healthStatus", value: value, descriptionText: "${device.displayName} healthStatus set to $value")
    sendEvent(name: "presence", value: "present")
    state.offlineCount = 0
  } else {
    state.offlineCount++
    if(state.offlineCount > 3) {
      sendEvent(name: "healthStatus", value: value, descriptionText: "${device.displayName} healthStatus set to $value")
      sendEvent(name: "presence", value: "not present")
      log.error "No response from IoTaWatt hub. Check connectivity."
    }
  }
}

def uninstalled() {
  log.warn "Uninstalling"
  unschedule()
  deleteAllChildDevices()
}

def deleteAllChildDevices() {
  log.warn "Uninstalling all child devices"
  getChildDevices().each {
        deleteChildDevice(it.deviceNetworkId)
      }
}

def ping() {
  refresh()
}

def logInfo(msg) {
  if (settings?.infoOutput) {
  log.info msg
  }
}

def logDebug(msg) {
  if (settings?.debugOutput) {
  log.debug msg
  }
}

def debugLogsOff(){
  log.warn "debug logging disabled..."
  device.updateSetting("debugOutput",[value:"false",type:"bool"])
}

def logTrace(msg) {
  if (settings?.traceOutput) {
  log.trace msg
  }
}

def traceLogsOff(){
  log.warn "debug logging disabled..."
  device.updateSetting("traceOutput",[value:"false",type:"bool"])
}

def getFormat(type, myText="") {
	if(type == "header") return "<div style='color:#660000;font-weight: bold'>${myText}</div>"
	if(type == "red") return "<div style='color:#660000'>${myText}</div>"
	if(type == "redBold") return "<div style='color:#660000;font-weight: bold;text-align: center;'>${myText}</div>"
	if(type == "importantBold") return "<div style='color:#32a4be;font-weight: bold'>${myText}</div>"
	if(type == "important") return "<div style='color:#32a4be'>${myText}</div>"
	if(type == "important2") return "<div style='color:#5a8200'>${myText}</div>"
	if(type == "important2Bold") return "<div style='color:#5a8200;font-weight: bold'>${myText}</div>"
	if(type == "lessImportant") return "<div style='color:green'>${myText}</div>"
	if(type == "rateDisplay") return "<div style='color:green; text-align: center;font-weight: bold'>${myText}</div>"
	if(type == "dull") return "<div style='color:black>${myText}</div>"
}