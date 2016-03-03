# Cordova-Plugin-Bluetooth-Printer
Cordova Plugin of Bluetooth Printer for Android Platform.

##Support
- Text
- POS Commands
- Image Printing (todo)
- Barcode Printing (todo)

##Install
Using the Cordova CLI and NPM, run:

```
cordova plugin add https://github.com/srehanuddin/Cordova-Plugin-Bluetooth-Printer.git
```



##Usage
Get list of paired bluetooth printers

```
BTPrinter.list(function(data){
        console.log("Success");
        console.log(data);
    },function(err){
        console.log("Error");
        console.log(err);
    })
```
